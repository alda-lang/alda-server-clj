(ns alda.worker
  (:require [alda.now        :as    now]
            [alda.parser     :refer (parse-input)]
            [alda.parser-util :refer (parse-to-events-with-context)]
            [alda.lisp.score :as    score]
            [alda.sound      :as    sound]
            [alda.sound.midi :as    midi]
            [alda.util       :as    util]
            [alda.version    :refer (-version-)]
            [cheshire.core   :as    json]
            [clojure.pprint  :refer (pprint)]
            [taoensso.timbre :as    log]
            [ezzmq.core      :as    zmq]))

(def ^:dynamic *no-system-exit* false)

(defn exit!
  [exit-code]
  (when-not *no-system-exit* (System/exit exit-code)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-alda-environment!
  []
  (sound/start-synthesis-engine!)
  (midi/open-midi-synth!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- success-response
  [body]
  {:success true
   :body    (if (map? body)
              (json/generate-string body)
              body)})

(defn- error-response
  [e]
  {:success false
   :body    (if (string? e)
              e
              (.getMessage e))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def current-status (ref :available))
(def current-score  (ref nil))
(def current-error  (ref nil))

(defn update-state!
  [status score error]
  (dosync
    (ref-set current-status status)
    (ref-set current-score score)
    (ref-set current-error error)))

(defn handle-code-play
  [code {:keys [history from to]}]
  (future
    (update-state! :parsing nil nil)
    (log/debug "Requiring alda.lisp...")
    (require '[alda.lisp :refer :all])
    (let [_ (log/debug "Parsing body...")
          [code-context code] (parse-to-events-with-context code)

          ;; If code was whitespace, normalize to ()
          code (or code ())

          ;; Parse and remove events
          _ (log/debug "Parsing history...")
          [history-context history] (parse-to-events-with-context history)

          ;; If history was whitespace, normalize to ()
          history (or history ())]
      (if-let [error (or (when (= :parse-failure code-context) code)
                         (when (= :parse-failure history-context) history))]
        (do
          (log/error error error)
          (update-state! :error nil error))
        (try
          (log/debug "Playing score...")
          (let [play-opts {:from     from
                           :to       to
                           :async?   true
                           :one-off? false}
                {:keys [score wait]}
                (if (empty? history)
                  (now/play-score! (score/score code) play-opts)
                  (now/with-score* (atom (-> (score/score)
                                             (score/continue history)))
                    (now/play-with-opts! play-opts code)))]
            (update-state! :playing score nil)
            (wait))
          (log/debug "Done playing score.")
          ;; become available, but hang onto the most recent score or error
          ;; until asked to start playing a new score
          (dosync (ref-set current-status :available))
          (catch Throwable e
            (log/error e e)
            (update-state! :error nil e))))))
  (success-response "Request received."))

(defn handle-code-parse
  [code & {:keys [mode] :or {mode :lisp}}]
  (try
    (require '[alda.lisp :refer :all])
    (success-response (case mode
                        :lisp (let [result (parse-input code mode)]
                                (with-out-str (pprint result)))
                        :map  (parse-input code mode)))
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
  (let [{:keys [as]} options]
    (case as
      "lisp" (handle-code-parse body :mode :lisp)
      "map"  (handle-code-parse body :mode :map)
      nil    (error-response "Missing option: as")
      (error-response (format "Invalid format: %s" as)))))

(defmethod process "ping"
  [_]
  (success-response "OK"))

(defmethod process "play"
  [{:keys [body options]}]
  (handle-code-play body options))

(defmethod process "play-status"
  [_]
  (let [status @current-status
        score  @current-score
        error  @current-error]
    (if (= :error status)
      (do
        (update-state! :available nil nil)
        (error-response error))
      (-> (success-response (name status))
          (assoc :score score)
          (assoc :pending (not (#{:available :playing} status)))))))

(defmethod process "version"
  [_]
  (success-response -version-))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const HEARTBEAT-INTERVAL 1000)
(def ^:const SUSPENDED-INTERVAL 10000)
(def ^:const MAX-LIVES          10)

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
          last-heartbeat (atom (System/currentTimeMillis))
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
             (if (and (not= :available @current-status)
                      (not= "play-status" command))
               (log/debug "Ignoring message. I'm busy.")
               (try
                 (log/debug "Processing message...")
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
                   (log/info "Error response sent.")))))

           :else
           (log/errorf "Invalid message: %s" (mapv #(String. %) msg))))]

        (while (and (zmq/polling?) @running?)
          (let [got-msgs (zmq/poll HEARTBEAT-INTERVAL)]
            (cond
              ; detect when the system has been suspended and stop working so
              ; the server can replace it with a fresh worker (this fixes a bug
              ; where MIDI audio is delayed)
              (> (System/currentTimeMillis)
                 (+ @last-heartbeat SUSPENDED-INTERVAL))
              (do
                (log/info "Process suspension detected. Shutting down...")
                (reset! last-heartbeat (System/currentTimeMillis))
                (reset! running? false))

              ; if a heartbeat wasn't received from the server within the
              ; acceptable threshold, MAX-LIVES times in a row, conclude that
              ; the server has stopped sending heartbeats and shut down
              (not (contains? got-msgs 0))
              (do
                (swap! lives dec)
                (when (and (<= @lives 0) (= :available @current-status))
                  (log/error "Unable to reach the server.")
                  (reset! running? false))))

            ; send AVAILABLE/BUSY status back to the server
            (when (and @running?
                       (> (System/currentTimeMillis)
                          (+ @last-heartbeat HEARTBEAT-INTERVAL)))
              (zmq/send-msg socket (if (= :available @current-status)
                                     "AVAILABLE"
                                     "BUSY"))
              (reset! last-heartbeat (System/currentTimeMillis))))))))
  (exit! 0))
