(ns alda.worker
  (:require [alda.parser                :refer (parse-input)]
            [alda.lisp.instruments.midi :refer (instruments)]
            [alda.lisp.score            :as    score]
            [alda.sound                 :as    sound]
            [alda.sound.midi            :as    midi]
            [alda.util                  :as    util]
            [alda.version               :refer (-version-)]
            [cheshire.core              :as    json]
            [clojure.core.cache         :as    cache]
            [clojure.set                :as    set]
            [taoensso.timbre            :as    log]
            [ezzmq.core                 :as    zmq]))

(def ^:dynamic *no-system-exit* false)

(defn exit!
  [exit-code]
  (when-not *no-system-exit* (System/exit exit-code)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-alda-environment!
  []
  (midi/open-midi-synth!)
  (midi/open-midi-sequencer!)
  (log/debug "Requiring alda.lisp...")
  (require '[alda.lisp :refer :all]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- success-response
  [body]
  {:success true
   :body    (if (or (map? body) (sequential? body))
              (json/generate-string body)
              body)})

(defn- error-response
  [e]
  {:success false
   :body    (if (string? e)
              e
              (.getMessage e))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Keep information about each job in memory for 2 hours.
(def ^:const JOB_CACHE_TTL (* 2 60 60 1000))

(defrecord Job [status score error stop!])

(def job-cache (atom (cache/ttl-cache-factory {} :ttl JOB_CACHE_TTL)))

(defn update-job! [id job]
  "Upserts a Job record into the cache.
   If a job with that ID is already in the cache, updates it.
   Otherwise, adds it to the cache."
  (swap! job-cache (fn [c]
                     (if (cache/has? c id)
                       (-> (cache/hit c id)
                           (assoc id job))
                       (cache/miss c id job)))))

(defn update-job-status! [id status]
  "Updates the `status` of a job, preserving the existing values of `score` and
   `error`.

   If the job doesn't exist yet, adds a new job with the provided `id` and
   `status`."
  (swap! job-cache (fn [c]
                     (if (cache/has? c id)
                       (-> (cache/hit c id)
                           (update id assoc :status status))
                       (cache/miss c id (Job. status nil nil nil))))))

(defn pending?
  [{:keys [status] :as job}]
  (not (#{:success :error :playing} status)))

(defn available?
  []
  (not-any? #(#{:parsing :playing} (:status %)) (vals @job-cache)))

(defn run-job!
  [code {:keys [history from to jobId]}]
  (try
    (log/debugf "Starting job %s..." jobId)
    (update-job! jobId (Job. :parsing nil nil nil))
    (let [events  (do
                    (log/debug "Parsing body...")
                    (parse-input code :output :events-or-error))
          history (when history
                    (log/debug "Parsing history...")
                    (parse-input history))
          score   (do
                    (log/debug "Constructing score...")
                    (apply score/continue
                           (or history (score/new-score))
                           events))

          {:keys [score stop! wait]}
          (do
            (log/debug "Playing score...")
            (sound/with-play-opts {:from     from
                                   :to       to
                                   :async?   true
                                   :one-off? false}
              (sound/play!
                score
                (when (seq (:events history))
                  (set/difference (:events score) (:events history))))))]
      (update-job! jobId (Job. :playing score nil stop!))
      (wait)
      (log/debug "Done playing score.")
      (update-job-status! jobId :success))
    (catch Throwable e
      (log/error e e)
      (update-job! jobId (Job. :error nil e nil)))))

(defn stop-playback!
  "Stops playback for all jobs where that is possible (i.e. ones that have a
   `:stop!` function)."
  []
  (doseq [[id {:keys [stop!] :as job}] @job-cache
          :when stop!]
    (stop!)
    (update-job! id (assoc job :status :success))))

(defn handle-code-play
  [code {:keys [jobId] :as options}]
  (-> (cond
        (empty? jobId)
        (error-response "Request missing a `jobId` option.")

        (get @job-cache jobId)
        (success-response "Already playing that score.")

        :else
        (do
          (future (run-job! code options))
          (success-response "Request received.")))
      (assoc :jobId jobId)))

(defn handle-code-parse
  [code {:keys [output] :as options}]
  (try
    (require '[alda.lisp :refer :all])
    (let [output (case output
                   ("data" nil) :score
                   "events"     :events-or-error)]
      (success-response (parse-input code :output output)))
    (catch Throwable e
      (log/error e e)
      (error-response e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti process :command)

(defmethod process :default
  [{:keys [command]}]
  (error-response (format "Unrecognized command: %s" command)))

(defmethod process nil
  [_]
  (error-response "Missing command"))

(defmethod process "parse"
  [{:keys [body options]}]
  (handle-code-parse body options))

(defmethod process "ping"
  [_]
  (success-response "OK"))

(defmethod process "play"
  [{:keys [body options]}]
  (handle-code-play body options))

(defn- sanitize-score-for-json
  "We need to convert the score (a Clojure map) into a JSON string to send as a
   response, but certain values in the map are not serializable as JSON."
  [score]
  (-> score
      ;; remove the audio-context (an atom containing information specific to
      ;; playing the score)
      (dissoc :audio-context)))

(defmethod process "play-status"
  [{:keys [options]}]
  (let [job-id (get options :jobId)
        {:keys [status score error] :as job} (get @job-cache job-id)]
    (-> (cond
          (empty? job-id)
          (error-response "Request missing a `jobId` option.")

          (nil? status)
          (error-response "Job not found.")

          (= :error status)
          (error-response error)

          :else
          (-> (success-response (name status))
              (assoc :score (sanitize-score-for-json score))
              (assoc :pending (pending? job))))
        (assoc :jobId job-id))))

(defmethod process "version"
  [_]
  (success-response -version-))

(defmethod process "instruments"
  [_]
  (-> (success-response "OK")
      (assoc :instruments (map first instruments))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const MIN-LIFESPAN       (* 1000 60 15)) ; 15 minutes
(def ^:const MAX-LIFESPAN       (* 1000 60 20)) ; 20 minutes
(def ^:const HEARTBEAT-INTERVAL 1000)  ; 1 second
(def ^:const SUSPENDED-INTERVAL 10000) ; 10 seconds
(def ^:const MAX-LIVES          30)

(def lives (atom MAX-LIVES))

(defn start-worker!
  [port & [verbose?]]
  (util/set-log-level! :debug)
  (when-not (or (System/getenv "ALDA_DEBUG") verbose?)
    (let [log-path (util/alda-home-path "logs" "error.log")]
      (log/infof "Logging errors to %s" log-path)
      (util/set-log-level! :error)
      (util/rolling-log! log-path)))
  (start-alda-environment!)
  (log/info "Worker reporting for duty!")
  (log/infof "Connecting to socket on port %d..." port)
  (zmq/with-new-context
    (let [running?       (atom true)
          now            (System/currentTimeMillis)
          lifespan       (+ now (rand-nth (range MIN-LIFESPAN MAX-LIFESPAN)))
          last-heartbeat (atom now)
          socket         (zmq/socket :dealer {:connect (str "tcp://*:" port)})]

      (zmq/after-shutdown
        (log/info "Shutting down."))

      (log/info "Sending READY signal.")
      (zmq/send-msg socket "READY")

      (zmq/polling {:stringify false}
        [socket :pollin [msg]
         (do
           (cond
           ; the server sends 1-frame messages as signals
            (= 1 (count msg))
            (let [signal (-> msg first (String.))]
              (case signal
                "KILL"      (do
                              (log/info "Received KILL signal from server.")
                              (reset! running? false))
                "STOP"      (do
                              (log/info "Received STOP signal from server.")
                              (stop-playback!))
                "HEARTBEAT" (do
                              (log/debug "Got HEARTBEAT from server.")
                              (reset! lives MAX-LIVES))
                (log/errorf "Invalid signal: %s" signal)))

           ; the server also forwards 3-frame messages from the client
           ; Frames:
           ;   1) the return address of the client
           ;   2) a JSON string representing the client's request
           ;   3) the command as a string (for use by the server)
            (= 3 (count msg))
            (let [[return-address body command] msg
                  body    (String. body)
                  command (String. command)]
              (try
                (when (and (not (available?))
                           (not= "play-status" command))
                  (log/debugf "Rejecting message (command: %s). I'm busy." command)
                  (throw (Exception. "The requested worker is not available.")))
                (log/debugf "Processing message... (command: %s)" command)
                (let [req (json/parse-string body true)
                      res (json/generate-string (process req))]
                  (log/debug "Sending response...")
                  (zmq/send-msg socket [return-address "" res])
                  (log/debug "Response sent."))
                (catch Throwable e
                  (log/error e e)
                  (log/info "Sending error response...")
                  (let [err (json/generate-string (error-response e))]
                    (zmq/send-msg socket [return-address "" err]))
                  (log/info "Error response sent."))))

            :else
            (log/errorf "Invalid message: %s" (mapv #(String. %) msg))))]

        (while (and (zmq/polling?) @running?)
          (let [now      (System/currentTimeMillis)
                got-msgs (zmq/poll HEARTBEAT-INTERVAL)]
            (cond
              ;; Each worker has a randomly assigned lifespan in the range of
              ;; MIN-LIFESPAN and MAX-LIFESPAN. Once this period of time has
              ;; elapsed, the worker finishes whatever work it might be doing
              ;; and then shuts down so that the server can replace it with a
              ;; fresh worker.
              ;;
              ;; This ensures that the workers available are always recently
              ;; spawned processes, which helps us avoid known audio bugs.
              (and (> now lifespan) (available?))
              (do
                (log/info "Worker lifespan elapsed. Shutting down...")
                (reset! last-heartbeat now)
                (reset! running? false))

              ;; Detect when the system has been suspended and stop working so
              ;; the server can replace it with a fresh worker.
              ;;
              ;; This fixes a bug where MIDI audio is delayed.
              (> now (+ @last-heartbeat SUSPENDED-INTERVAL))
              (do
                (log/info "Process suspension detected. Shutting down...")
                (reset! last-heartbeat now)
                (reset! running? false))

              ;; If a heartbeat wasn't received from the server within the
              ;; acceptable threshold, MAX-LIVES times in a row, conclude that
              ;; the server has stopped sending heartbeats and shut down.
              (not (contains? got-msgs 0))
              (do
                (swap! lives dec)
                (when (and (<= @lives 0) (available?))
                  (log/error "Unable to reach the server.")
                  (reset! running? false))))

            ;; Send AVAILABLE/BUSY status back to the server.
            (when (and @running?
                       (> now (+ @last-heartbeat HEARTBEAT-INTERVAL)))
              (zmq/send-msg socket (if (available?) "AVAILABLE" "BUSY"))
              (reset! last-heartbeat now)))))))
  (exit! 0))
