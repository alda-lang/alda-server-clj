(ns alda.zmq-util
  (:import [java.net ServerSocket]
           [org.zeromq ZMsg]))

(defn find-open-port
  []
  (let [tmp-socket (ServerSocket. 0)
        port       (.getLocalPort tmp-socket)]
    (.close tmp-socket)
    port))

(defn respond-to
  [msg socket response & [envelope]]
  (let [envelope (or envelope (.unwrap msg))
        msg      (doto (ZMsg/newStringMsg (into-array String [response]))
                   (.wrap envelope))]
    (.send msg socket)))

