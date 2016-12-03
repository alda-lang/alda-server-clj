(ns alda.zmq-util
  (:require [ezzmq.core :as zmq])
  (:import [java.net ServerSocket]))

(defn find-open-port
  []
  (let [tmp-socket (ServerSocket. 0)
        port       (.getLocalPort tmp-socket)]
    (.close tmp-socket)
    port))

(defn respond-to
  [[address] socket response]
  (zmq/send-msg socket [address "" response]))

