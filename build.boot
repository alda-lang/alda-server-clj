(set-env!
  :source-paths   #{"src" "test"}
  :resource-paths #{"grammar" "examples"}
  :dependencies   '[
                    ; dev
                    [adzerk/bootlaces "0.1.13" :scope "test"]
                    [adzerk/boot-test "1.0.4"  :scope "test"]

                    ; server / worker
                    [org.clojure/clojure    "1.8.0"]
                    [instaparse             "1.4.1"]
                    [io.aviso/pretty        "0.1.20"]
                    [com.taoensso/timbre    "4.1.1"]
                    [cheshire               "5.6.3"]
                    [djy                    "0.1.4"]
                    [jline                  "2.12.1"]
                    [org.clojars.sidec/jsyn "16.7.3"]
                    [potemkin               "0.4.1"]
                    [org.zeromq/cljzmq      "0.1.4" :exclusions (org.zeromq/jzmq)]
                    [me.raynes/conch        "0.8.0"]
                    [clj_manifest           "0.2.0"]
                    [org.zeromq/jeromq      "0.3.5"]])

(require '[adzerk.bootlaces :refer :all]
         '[adzerk.boot-test])

(def ^:const +version+ "0.0.1")

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

  target  {:dir #{"target"}})

(deftask heading
  [t text TEXT str "The text to display."]
  (with-pass-thru fs
    (println)
    (println "---" text "---")))

(deftask unit-tests
  []
  (comp (heading :text "UNIT TESTS")
        (adzerk.boot-test/test
          :namespaces '#{; general tests
                         alda.parser.barlines-test
                         alda.parser.clj-exprs-test
                         alda.parser.event-sequences-test
                         alda.parser.comments-test
                         alda.parser.duration-test
                         alda.parser.events-test
                         alda.parser.octaves-test
                         alda.parser.repeats-test
                         alda.parser.score-test
                         alda.parser.variables-test
                         alda.lisp.attributes-test
                         alda.lisp.cram-test
                         alda.lisp.chords-test
                         alda.lisp.code-test
                         alda.lisp.duration-test
                         alda.lisp.global-attributes-test
                         alda.lisp.markers-test
                         alda.lisp.notes-test
                         alda.lisp.parts-test
                         alda.lisp.pitch-test
                         alda.lisp.score-test
                         alda.lisp.variables-test
                         alda.lisp.voices-test
                         alda.util-test

                         ; benchmarks / smoke tests
                         alda.examples-test})))

(deftask integration-tests
  []
  (comp (heading :text "INTEGRATION TESTS")
        (adzerk.boot-test/test
          :namespaces '#{alda.server-test
                         alda.worker-test})))

(deftask test
  [i integration bool "Run only integration tests."
   a all         bool "Run all tests."]
  (cond
    all         (comp (unit-tests)
                      (integration-tests))
    integration (integration-tests)
    :default    (unit-tests)))

(deftask dev
  "Runs the Alda server (default), worker, or REPL for development.

   *** REPL ***

   Simply run `boot dev -a repl` and you're in!

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
  [a app              APP     str  "The Alda application to run (server, repl or client)."
   x args             ARGS    str  "The string of CLI args to pass to the client."
   p port             PORT    int  "The port on which to start the server/worker."
   w workers          WORKERS int  "The number of workers for the server to start."
   F alda-fingerprint         bool "Allow the Alda client to identify this as an Alda process."]
  (comp
    (javac)
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
                             ((resolve 'alda.worker/start-worker!) port true))
            start-repl!    (fn []
                             (require 'alda.repl)
                             ((resolve 'alda.repl/start-repl!)))]
        (case app
          nil      (start-server!)
          "server" (start-server!)
          "worker" (start-worker!)
          "repl"   (start-repl!)
          (do
            (println "ERROR: -a/--app must be server, worker or repl")
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

