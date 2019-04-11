(ns alda.server
  (:require [alda.queue                :as    q]
            [alda.util                 :as    util]
            [alda.version              :refer (-version-)]
            [alda.zmq-util             :refer (find-open-port respond-to)]
            [cheshire.core             :as    json]
            [ezzmq.core                :as    zmq]
            [me.raynes.conch.low-level :as    sh]
            [taoensso.timbre           :as    log])
  (:import [java.util.concurrent ConcurrentLinkedQueue]
           [org.zeromq ZMQException]))

(def ^:dynamic *no-system-exit* false)
(def ^:dynamic *disable-supervisor* false)

(defn exit!
  [exit-code]
  (when-not *no-system-exit* (System/exit exit-code)))

; the number of ms between heartbeats
(def ^:const HEARTBEAT-INTERVAL 1000)
; the amount of missed heartbeats before a worker is pronounced dead
(def ^:const WORKER-LIVES          3)

; the number of ms between checking on the number of workers
(def ^:const WORKER-CHECK-INTERVAL 60000)

; the number of ms to wait between starting workers
; (starting them all at the same time causes heavy CPU usage)
(def ^:const WORKER-START-INTERVAL 10000)

; the number of ms since last server heartbeat before we conclude the process has
; been suspended (e.g. laptop lid closed)
(def ^:const SUSPENDED-INTERVAL 10000)

(defn worker-expiration-date []
  (+ (System/currentTimeMillis) (* HEARTBEAT-INTERVAL WORKER-LIVES)))

(defn worker [address]
  {:address address
   :expiry  (worker-expiration-date)})

(def available-workers (q/queue))
(def busy-workers      (ref #{}))
(def worker-blacklist  (ref #{}))
(defn all-workers []   (->> @available-workers
                            (concat (for [address @busy-workers]
                                      {:address address}))
                            (remove #(contains? @worker-blacklist (:address %)))
                            doall))

(defn- friendly-id
  [address]
  (->> address
       (map int)
       (apply str)))

; (doseq [[x y] [[:available available-workers]
;                [:busy      busy-workers]
;                [:blacklist worker-blacklist]]]
;   (add-watch y :key (fn [_ _ old new]
;                       (when (not= old new)
;                         (prn x (for [worker new]
;                                  (conj []
;                                        (if-let [address (:address worker)]
;                                          (friendly-id address)
;                                          (friendly-id worker))
;                                        (when-let [expiry (:expiry worker)]
;                                          expiry))))))))

(defn- json-response
  [success?]
  (fn [body]
    (json/generate-string {:success success?
                           :body body
                           :noWorker true})))

(def successful-response   (json-response true))
(def unsuccessful-response (json-response false))

(def pong-response (successful-response "PONG"))

(def shutting-down-response (successful-response "Shutting down..."))

(def stopping-playback-response (successful-response "Stopping playback..."))

(defn status-response
  [available total backend-port]
  (successful-response
    (format "Server up (%s/%s workers available, backend port: %s)"
            available
            total
            backend-port)))

(def version-response (successful-response -version-))

(def no-workers-available-response
  (unsuccessful-response
    "No worker processes are ready yet. Please wait a minute."))

(def all-workers-are-busy-response
  (unsuccessful-response
    (str "All worker processes are currently busy. Please wait until playback "
         "is complete and try again.")))

(defn remove-worker-from-queue
  [address]
  (dosync
    (alter busy-workers disj address)
    (q/remove-from-queue available-workers #(= address (:address %)))))

(defn add-or-requeue-worker
  [address]
  (dosync
    (remove-worker-from-queue address)
    (q/push-queue available-workers (worker address))))

(defn note-that-worker-is-busy
  [address]
  (dosync
    (q/remove-from-queue available-workers #(= address (:address %)))
    (alter busy-workers conj address)))

(defn fire-lazy-workers!
  []
  (dosync
    (q/remove-from-queue available-workers
                         #(< (:expiry %) (System/currentTimeMillis)))))

(defn blacklist-worker!
  [address]
  (dosync (alter worker-blacklist conj address))
  (future
    (Thread/sleep 30000)
    (dosync (alter worker-blacklist disj address))))

(defn lay-off-worker!
  "Lays off the last worker in the queue. Bad luck on its part.

   To 'lay off' a worker, we cannot simply send a KILL message, otherwise the
   worker may die without finishing its workload (e.g. if it's in the middle of
   playing a score). We want the worker to finish what it's doing and then shut
   down.

   To do this, we...
   - remove it from the queue
   - temporarily 'blacklist' its address so it won't be re-queued the next time
     it sends a heartbeat
   - in a separate thread, wait a safe amount of time and then remove the
     address from the blacklist; this is to keep the blacklist from growing too
     big over time

   Once the worker is out of the queue and we aren't letting it back in, the
   worker will stop getting heartbeats from the server and shut itself down."
  []
  (dosync
    (let [{:keys [address]} (q/reverse-pop-queue available-workers)]
      (blacklist-worker! address))))

(defn start-workers!
  [workers port]
  (let [program-path (util/program-path)
        cmd (if (re-find #"clojure.*jar$" program-path)
              ; this means we are running the `boot dev` task, and the "program
              ; path" ends up being clojure-<version>.jar instead of alda; in
              ; this scenario, we can use the `boot dev` task to start each
              ; worker
              ["boot" "dev" "--alda-fingerprint" "-a" "worker"
                                                 "--port" (str port)]
              ; otherwise, use the same program that was used to start the
              ; server (e.g. /usr/local/bin/alda)
              [program-path "--port" (str port) "--alda-fingerprint" "worker"])]
    (future
      (dotimes [_ workers]
        (let [{:keys [in out err]} (apply sh/proc cmd)]
          (.close in)
          (.close out)
          (.close err))
        (Thread/sleep WORKER-START-INTERVAL)))))

(defn supervise-workers!
  "Ensures that there are at least `desired` number of workers available by
   counting how many we have and starting more if needed."
  [port desired]
  (let [current (count (all-workers))
        needed  (- desired current)]
    (cond
      (pos? needed)
      (do
        (log/info "Supervisor says we need more workers.")
        (log/infof "Starting %s more worker(s)..." needed)
        (start-workers! needed port))

      (neg? needed)
      (do
        (log/info "Supervisor says there are too many workers.")
        (log/infof "Laying off %s worker(s)..." (- needed))
        (dotimes [_ (- needed)]
          (lay-off-worker!)
          (Thread/sleep 100)))

      :else
      (log/debug "Supervisor approves of the current number of workers."))))

(defn stop-playback!
  [backend]
  (doseq [{:keys [address]} (all-workers)]
    (zmq/send-msg backend [address "STOP"])))

(defn murder-workers!
  [backend shutting-down?]
  (doseq [{:keys [address]} (all-workers)]
    (zmq/send-msg backend [address "KILL"])
    (when-not shutting-down?
      (blacklist-worker! address))))

(defn cycle-workers!
  [backend port workers]
  ; kill workers (this might only get the busy ones)
  (murder-workers! backend false)
  ; clear out the worker queues
  (dosync
    (alter available-workers empty)
    (alter busy-workers empty))
  ; start new workers
  (start-workers! workers port))

(def running? (atom true))

(defn shut-down!
  [backend]
  (log/info "Murdering workers...")
  (murder-workers! backend true)
  (reset! running? false))

(defn start-server!
  [workers frontend-port & [verbose?]]
  (util/set-log-level! (if verbose? :debug :info))
  (zmq/with-new-context
    (let [backend-port    (find-open-port)
          last-heartbeat  (atom (System/currentTimeMillis))
          last-supervised (atom (System/currentTimeMillis))
          _               (log/infof "Binding frontend socket on port %s..."
                                     frontend-port)
          frontend        (try
                            (zmq/socket
                              :router
                              {:bind (str "tcp://*:" frontend-port)})
                            (catch ZMQException e
                              (if (= 48 (.getErrorCode e))
                                (log/error
                                  (str "There is already an Alda server "
                                       "running on this port."))
                                (throw e))
                              (exit! 1)))
          _               (log/infof "Binding backend socket on port %s..."
                                     backend-port)
          backend         (zmq/socket
                            :router
                            {:bind (str "tcp://*:" backend-port)})]

      (zmq/before-shutdown
        (log/info "Interrupt (e.g. Ctrl-C) received.")
        (when @running? (shut-down! backend))
        (log/info "Shutting down..."))

      (zmq/after-shutdown
        (log/info "Exiting."))

      (log/infof "Spawning %s workers..." workers)
      (start-workers! workers backend-port)

      (zmq/polling {:stringify false}
        [backend :pollin [[address & msg]]
         (if (= 1 (count msg))
           (let [signal (-> msg first (String.))]
             (when-not (contains? @worker-blacklist address)
               (case signal
                 "BUSY"      (note-that-worker-is-busy address)
                 "AVAILABLE" (add-or-requeue-worker address)
                 "READY"     (add-or-requeue-worker address)
                 "DONE"      (remove-worker-from-queue address)
                 (log/errorf "Invalid signal: %s" signal))))
           (do
             (log/debug "Forwarding backend response to frontend...")
             (zmq/send-msg frontend (conj (vec msg) address))))

         frontend :pollin [msg]
         (let [cmd (-> msg last (String.))]
           (case cmd
             ; the server responds directly to certain commands
             "ping"
             (respond-to msg frontend pong-response)

             ("play-status" "export-status")
             (let [[client-address request address] msg]
               (log/debugf "Forwarding message to worker %s..."
                           (friendly-id address))
               (zmq/send-msg backend [address client-address request cmd]))

             "status"
             (respond-to msg frontend
                         (status-response (count @available-workers)
                                          workers
                                          backend-port))

             "stop-playback"
             (do
               (respond-to msg frontend stopping-playback-response)
               (stop-playback! backend))

             "stop-server"
             (do
               (respond-to msg frontend shutting-down-response)
               (shut-down! backend))

             "version"
             (respond-to msg frontend version-response)

             ; any other message is forwarded to the next available
             ; worker
             (cond
               (not (empty? @available-workers))
               (let [{:keys [address]}
                     (dosync (q/pop-queue available-workers))]
                 (log/debugf "Forwarding message to worker %s..."
                             (friendly-id address))
                 (zmq/send-msg backend (vec (cons address msg))))

               ; if no workers are available, respond immediately so the
               ; client isn't left waiting
               (not (empty? @busy-workers))
               (do
                 (log/debug (str "All workers are currently busy. "
                                 "Letting the client know..."))
                 (respond-to msg frontend
                             all-workers-are-busy-response))

               :else
               (do
                 (log/debug (str "Workers not ready yet. "
                                 "Letting the client know..."))
                 (respond-to msg frontend
                             no-workers-available-response)))))]

        (while @running?
          (zmq/poll HEARTBEAT-INTERVAL)

          ; purge workers we haven't heard from in too long
          (fire-lazy-workers!)

          ; detect when the system has been suspended and cycle workers
          ; (fixes a bug where MIDI audio is delayed)
          (when (> (System/currentTimeMillis)
                   (+ @last-heartbeat SUSPENDED-INTERVAL))
            (log/info "Process suspension detected. Cycling workers...")
            (cycle-workers! backend backend-port workers)
            (reset! last-heartbeat  (System/currentTimeMillis))
            (reset! last-supervised (System/currentTimeMillis)))

          ; make sure we still have the desired number of workers
          (when (> (System/currentTimeMillis)
                   (+ @last-supervised WORKER-CHECK-INTERVAL))
            (reset! last-supervised (System/currentTimeMillis))
            (when-not (or *disable-supervisor*
                          (System/getenv "ALDA_DISABLE_SUPERVISOR"))
              (supervise-workers! backend-port workers)))

          ; send a heartbeat to all current workers
          (when (> (System/currentTimeMillis)
                   (+ @last-heartbeat HEARTBEAT-INTERVAL))
            (reset! last-heartbeat (System/currentTimeMillis))
            (doseq [{:keys [address]} (all-workers)]
              (zmq/send-msg backend [address "HEARTBEAT"])))))))
  (exit! 0))

