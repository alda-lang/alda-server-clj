(ns alda.worker-test
  (:require [clojure.test  :refer :all]
            [cheshire.core :as    json]
            [ezzmq.core    :as    zmq]
            [alda.worker   :as    worker]
            [alda.zmq-util :refer (find-open-port)]
            [alda.version  :refer (-version-)])
  (:import [java.nio.file Files Paths]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *port*   nil)
(def ^:dynamic *socket* nil)

(defn- init!
  [var val]
  (alter-var-root var (constantly val)))

(defn- generate-job-id
  []
  (letfn [(rand-char [] (char (rand-nth (range (int \a) (inc (int \z))))))]
    (apply str (repeatedly 20 rand-char))))

(defn response-for-msg
  [msg]
  (zmq/send-msg *socket* msg)
  (-> (zmq/receive-msg *socket* :stringify true)
      second
      (json/parse-string true)))

(defn response-for
  [{:keys [command] :as req}]
  (let [msg [(json/generate-string req) command]]
    (response-for-msg msg)))

(defn complete-response-for
  [{:keys [command] :as req}]
  (let [msg [(json/generate-string req) command]]
    (zmq/send-msg *socket* msg))
  (zmq/receive-msg *socket* :stringify true))

(defn response-for-msg
  [msg]
  (zmq/send-msg *socket* msg)
  (loop [res (zmq/receive-msg *socket* :stringify true)]
    (if (#{"READY" "AVAILABLE" "BUSY"} (first res))
      (recur (zmq/receive-msg *socket* :stringify true))
      res)))

(defn response-for
  [{:keys [command] :as req}]
  (let [msg [(.getBytes "fakeclientaddress")
             (json/generate-string req)
             command]]
    (response-for-msg msg)))

(use-fixtures :once
  (fn [run-tests]
    (zmq/with-new-context
      (init! #'*port*   (find-open-port))
      (init! #'*socket* (zmq/socket
                          :dealer
                          {:bind (format "tcp://*:%s" *port*)}))
      (zmq/worker-thread {:on-interrupt #(println "Worker interrupted!")}
        (Thread/sleep 1000) ; to make sure we don't miss the READY signal
        (binding [worker/*no-system-exit* true]
          (worker/start-worker! *port* true)))
      (run-tests))))

(deftest worker-tests
  (testing "a worker process"
    (testing "should send a READY signal when it starts up"
      (let [[msg] (zmq/receive-msg *socket* :stringify true)]
        (is (= "READY" msg))))
    (testing "should send AVAILABLE heartbeats while waiting for work"
      (dotimes [_ 5]
        (let [[msg] (zmq/receive-msg *socket* :stringify true)]
          (is (= "AVAILABLE" msg)))))
    ;; NOTE: the expected responses are tested in alda.server-test
    (testing "should successfully respond to"
      (testing "a 'parse' command"
        (let [req {:command "parse"
                   :body "piano: c8 d e f g2"}
              [_ _ json :as res] (response-for req)
              {:keys [success body]} (json/parse-string json true)]
          (is success body)))
      (let [job-id (generate-job-id)]
        (testing "a 'play' command"
          (let [req {:command "play"
                     :body "piano: (vol 0) c2"
                     :options {:jobId job-id}}
                [_ _ json] (response-for req)
                {:keys [success body]} (json/parse-string json true)]
            (is success body)))
        ;; TIMING: give the worker time to start processing the job
        (Thread/sleep 1000)
        (testing "a 'play-status' command"
          (let [req {:command "play-status" :options {:jobId job-id}}
                [_ _ json] (response-for req)
                {:keys [success body] :as res} (json/parse-string json true)]
            (is success body))))
      (let [job-id (generate-job-id)]
        (testing "an 'export' command"
          (let [tmp-dir  (Files/createTempDirectory
                           "alda-worker-test"
                           (into-array FileAttribute []))
                filename (-> tmp-dir
                             .toString
                             (Paths/get (into-array String [job-id]))
                             .toAbsolutePath
                             (str ".mid"))
                req {:command "export"
                     :body    "piano: (vol 0) c2"
                     :options {:jobId job-id, :filename filename}}
                [_ _ json]             (response-for req)
                {:keys [success body]} (json/parse-string json true)]
            (is success body)))
        ;; TIMING: give the worker time to start processing the job
        (Thread/sleep 1000)
        (testing "a 'job-status' command"
          (let [req {:command "job-status" :options {:jobId job-id}}
                [_ _ json] (response-for req)
                {:keys [success body] :as res} (json/parse-string json true)]
            (is success body)))))
    (testing "should accept signals from the server"
      (doseq [signal ["HEARTBEAT" "STOP" "KILL"]]
        (zmq/send-msg *socket* signal)))))
