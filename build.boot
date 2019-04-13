(set-env!
  :source-paths #{"src" "test"}
  :dependencies
  '[; dev
    [adzerk/bootlaces      "0.1.13" :scope "test"]
    [adzerk/boot-test      "1.2.0"  :scope "test"]
    [alda/core             "LATEST" :scope "test"]
    [alda/sound-engine-clj "LATEST" :scope "test"]

    ; server / worker
    [com.taoensso/timbre    "4.10.0" :exclusions [org.clojure/clojure]]
    [cheshire               "5.7.1"]
    [io.djy/ezzmq           "0.5.3"]
    [me.raynes/conch        "0.8.0"]
    [org.clojure/core.cache "0.6.5"]])

(require '[adzerk.bootlaces :refer :all]
         '[adzerk.boot-test :refer :all])

(def ^:const +version+ "0.5.0")

(bootlaces! +version+)

(task-options!
  pom     {:project 'alda/server-clj
           :version +version+
           :description "A Clojure implementation of an Alda server"
           :url "https://github.com/alda-lang/alda-server-clj"
           :scm {:url "https://github.com/alda-lang/alda-server-clj"}
           :license {"name" "Eclipse Public License"
                     "url" "http://www.eclipse.org/legal/epl-v10.html"}}

  jar     {:file "alda-server.jar"}

  install {:pom "alda/server-clj"}

  target  {:dir #{"target"}}

  test    {:include #"-test$"})

(deftask dev
  "Runs the Alda server (default) or worker for development.

   *** SERVER ***

   The -F/--alda-fingerprint option technically does nothing, but including it
   as a long-style option when running this task from the command line* allows
   the Alda client to identify the dev server process as an Alda server and
   include it in the list of running servers.

   For example:

      boot dev -a server --port 27713 --alda-fingerprint

   Take care to include the --port long option as well, so the client knows
   the port on which the dev server is running.

   *** WORKER ***

   Starts a worker process that will receive requests from the socket on the
   port specified by the -p/--port option. This option is required."
  [a app              APP     str  "The Alda application to run (server or worker)."
   x args             ARGS    str  "The string of CLI args to pass to the client."
   p port             PORT    int  "The port on which to start the server/worker."
   w workers          WORKERS int  "The number of workers for the server to start."
   F alda-fingerprint         bool "Allow the Alda client to identify this as an Alda process."]
  (comp
    (with-pass-thru fs
      (let [start-server!  (fn []
                             (require 'alda.server)
                             (require 'alda.util)
                             ((resolve 'alda.server/start-server!)
                              (or workers 2)
                              (or port 27713)
                              true))
            start-worker!  (fn []
                             (assert port
                               "The --port option is mandatory for workers.")
                             (require 'alda.worker)
                             (require 'alda.util)
                             ((resolve 'alda.worker/start-worker!) port true))]
        (case app
          nil      (start-server!)
          "server" (start-server!)
          "worker" (start-worker!)
          (do
            (println "ERROR: -a/--app must be server or worker")
            (System/exit 1)))))
    (wait)))

(deftask package
  "Builds jar file."
  []
  (comp (pom)
        (jar)))

(deftask deploy
  "Builds jar file, installs it to local Maven repo, and deploys it to Clojars."
  []
  (comp (package) (install) (push-release)))

