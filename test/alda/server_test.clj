(ns alda.server-test
  (:require [clojure.test  :refer :all]
            [cheshire.core :as    json]
            [ezzmq.core    :as    zmq]
            [alda.server   :as    server]
            [alda.util     :as    util]
            [alda.version  :refer (-version-)]
            [alda.zmq-util :refer (find-open-port)])
  (:import [java.io File]
           [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]
           [javax.sound.midi MidiSystem Sequence]))

(def ^:dynamic *frontend-port*   nil)
(def ^:dynamic *backend-port*    nil)
(def ^:dynamic *frontend-socket* nil)
(def ^:dynamic *backend-socket*  nil)

(defn- init!
  [var val]
  (alter-var-root var (constantly val)))

(defn start-server!
  []
  (zmq/worker-thread {:on-interrupt #(println "Server interrupted!")}
    (binding [server/*no-system-exit* true]
      (server/start-server! 2 *frontend-port*))))

(defn- generate-job-id
  []
  (letfn [(rand-char [] (char (rand-nth (range (int \a) (inc (int \z))))))]
    (apply str (repeatedly 20 rand-char))))

(defn play-status-request
  [worker-address job-id]
  [(json/generate-string {:command "play-status" :options {:jobId job-id}})
   worker-address
   "play-status"])

(defn export-status-request
  [worker-address job-id]
  [(json/generate-string {:command "export-status" :options {:jobId job-id}})
   worker-address
   "export-status"])

(defn response-for-msg
  [msg]
  (zmq/send-msg *frontend-socket* msg)
  (-> (zmq/receive-msg *frontend-socket* :stringify true)
      second
      (json/parse-string true)))

(defn response-for
  [{:keys [command] :as req}]
  (let [msg [(json/generate-string req) command]]
    (response-for-msg msg)))

(defn complete-response-for
  [{:keys [command] :as req}]
  (let [msg [(json/generate-string req) command]]
    (zmq/send-msg *frontend-socket* msg))
  (zmq/receive-msg *frontend-socket* :stringify false))

(defn get-backend-port
  []
  (let [req {:command "status"}
        res (response-for req)]
    (->> (:body res)
         (re-find #"backend port: (\d+)")
         second)))

(defn wait-for-a-worker
  []
  (let [workers (->> (response-for {:command "status"})
                     :body
                     (re-find #"(\d+)/\d+ workers available")
                     second
                     Integer/parseInt)]
    (when (zero? workers) (Thread/sleep 100) (recur))))

(use-fixtures :once
  (fn [run-tests]
    (zmq/with-new-context
      (init! #'*frontend-port*   (find-open-port))
      (init! #'*frontend-socket* (zmq/socket
                                   :dealer
                                   {:connect (format "tcp://*:%s"
                                                     *frontend-port*)}))
      (start-server!)
      (init! #'*backend-port*   (get-backend-port))
      (init! #'*backend-socket* (zmq/socket
                                  :dealer
                                  {:connect (format "tcp://*:%s"
                                                    *backend-port*)}))
      (run-tests))))

(deftest frontend-tests
  (testing "the 'ping' command"
    (let [req {:command "ping"}]
      (testing "gets a successful response"
        (is (:success (response-for req))))))
  (testing "the 'status' command"
    (let [req {:command "status"}
          res (-> (response-for req) :body)]
      (testing "says the server is up"
        (is (re-find #"Server up" res)))
      (testing "has a response that includes"
        (testing "the number of available workers"
          (is (re-find #"\d+/\d+ workers available" res)))
        (testing "the backend port number"
          (is (re-find #"backend port: \d+" res))))))
  (testing "the 'version' command"
    (let [req {:command "version"}
          res (-> (response-for req) :body)]
      (testing "reports the current version"
        (is (re-find (re-pattern -version-) res)))))
  (testing "until the first worker process is available,"
    (testing "the 'parse' command"
      (let [req {:command "parse" :body "it doesn't matter what i put here"}]
        (testing "gets a 'no workers available yet' response"
          (let [{:keys [success body]} (response-for req)]
            (is (not success))
            (is (re-find #"No worker processes are ready yet" body)))))))
  (testing "once there is a worker available,"
    (println "Waiting for a worker process to become available...")
    (wait-for-a-worker)
    (println "Worker ready.")
    (testing "the 'parse' command"
      (let [req {:command "parse" :body "piano: c"}
            {:keys [success body]} (response-for req)]
        (testing "should get a successful response containing a score map"
          (is success body)
          (let [score-map (json/parse-string body true)]
            (is (contains? score-map :events))
            (is (contains? score-map :instruments))))))
    (wait-for-a-worker)
    (let [job-id (generate-job-id)
          req {:command "play"
               ;; forcing parsing to take at least 2 seconds for play-status
               ;; test below
               :body    "piano: (Thread/sleep 2000) (vol 0) c2"
               :options {:jobId job-id}}
          [_ json worker-address] (complete-response-for req)
          {:keys [success body jobId]} (json/parse-string (String. json) true)]
      (testing "the play command"
        (testing "should get a successful response"
          (is success body)
          (testing "that includes the address of the worker playing the score"
            (is (not (nil? worker-address))))
          (testing "that includes the correct job ID"
            (is (= job-id jobId)))))
      ;; TIMING: wait briefly to ensure the worker has started working
      (Thread/sleep 250)
      (testing "the play-status command"
        (let [req (play-status-request worker-address job-id)
              {:keys [success pending body jobId]} (response-for-msg req)]
          (testing "should get a successful response"
            (is success body)
            (testing "that includes the correct job ID"
              (is (= job-id jobId))))
          (testing "should say the status is 'parsing' while the worker is parsing"
            (is (= "parsing" body))
            (testing "and 'pending' should be true"
              (is pending)))
          (testing "should indicate success once playback completes"
            (let [timeout 5000
                  start   (System/currentTimeMillis)]
              (loop []
                (let [now (System/currentTimeMillis)
                      req (play-status-request worker-address job-id)
                      {:keys [success pending body]} (response-for-msg req)]
                  (cond
                    (not success)
                    (is false body)

                    (= "success" body)
                    (is (not pending) body)

                    (-> now (- start) (> timeout))
                    (is false "Timeout exceeded.")

                    :else
                    (do (Thread/sleep 100) (recur))))))))))

    (wait-for-a-worker)
    (let [job-id   (generate-job-id)
          tmp-dir  (Files/createTempDirectory
                     "alda-server-test"
                     (into-array FileAttribute []))
          filename (-> tmp-dir
                       .toString
                       (Paths/get (into-array String [job-id]))
                       .toAbsolutePath
                       (str ".mid"))
          req      {:command "export"
                    ;; forcing parsing to take at least 2 seconds for
                    ;; export-status test below
                    :body    "piano: (Thread/sleep 2000) (vol 0) c2"
                    :options {:jobId job-id
                              :filename filename}}
          [_ json worker-address]      (complete-response-for req)
          {:keys [success body jobId]} (json/parse-string (String. json) true)]
      (testing "the export command"
        (testing "should get a successful response"
          (is success body)
          (testing "that includes the address of the worker"
            (is (not (nil? worker-address))))
          (testing "that includes the correct job ID"
            (is (= job-id jobId)))))
      ;; TIMING: wait briefly to ensure the worker has started working
      (Thread/sleep 250)
      (testing "the export-status command"
        (let [req (export-status-request worker-address job-id)
              {:keys [success pending body jobId]} (response-for-msg req)]
          (testing "should get a successful response"
            (is success body)
            (testing "that includes the correct job ID"
              (is (= job-id jobId))))
          (testing "should say the status is 'parsing' while the worker is parsing"
            (is (= "parsing" body))
            (testing "and 'pending' should be true"
              (is pending)))
          (testing "should indicate success once the export completes"
            (let [timeout 5000
                  start   (System/currentTimeMillis)]
              (loop []
                (let [now (System/currentTimeMillis)
                      req (export-status-request worker-address job-id)
                      {:keys [success pending body]} (response-for-msg req)]
                  (cond
                    (not success)
                    (is false body)

                    (= "success" body)
                    (is (not pending) body)

                    (-> now (- start) (> timeout))
                    (is false "Timeout exceeded.")

                    :else
                    (do (Thread/sleep 100) (recur)))))))))
      (testing "the exported MIDI file"
        (let [file (File. filename)]
          (testing "exists"
            (is (.exists file) (format "%s doesn't exist" filename)))
          (testing "is a MIDI file"
            (let [sqnc (MidiSystem/getSequence file)]
              (is (instance? Sequence sqnc) (type sqnc))
              (testing "with the expected division type and resolution"
                (let [division-type (.getDivisionType sqnc)]
                  (is (= Sequence/PPQ division-type) division-type))
                (let [resolution (.getResolution sqnc)]
                  (is (= 128 resolution) resolution))))))))
    (testing "the 'stop-server' command"
      (let [req {:command "stop-server"}]
        (testing "gets a successful response"
          (is (:success (response-for req))))))))

(deftest backend-tests
  (comment
    "In these tests, we are acting like a worker process interacting with
     the server we are testing.

     The backend receives two different types of messages from workers:
     - responses to forward to the client that made the request
     - heartbeats

     It is difficult to test the first type of message, because the server
     takes it and forwards it to the client, and does not send anything back
     to the worker to let us know that this happened.

     The frontend tests above actually confirm that this works properly; if
     the server has at least one worker running, then if you send a message
     to the frontend and the server sends a response from the worker, then
     we know that the first type of message works.

     Testing the second type of message here. As a worker, if we send a READY
     or AVAILABLE heartbeat to the server, it should start sending us HEARTBEAT
     signals.")
  (testing "the backend socket"
    (testing "can send and receive heartbeats"
      (zmq/send-msg *backend-socket* "READY")
      (let [[msg] (zmq/receive-msg *backend-socket* :stringify true)]
        (is (= "HEARTBEAT" msg)))
      (dotimes [_ 5]
        (zmq/send-msg *backend-socket* "AVAILABLE")
        (let [[msg] (zmq/receive-msg *backend-socket* :stringify true)]
          (is (= "HEARTBEAT" msg)))))
    (testing "can send DONE signal"
      (zmq/send-msg *backend-socket* "DONE"))))
