# alda-server-clj

A Clojure implementation of an [Alda](https://github.com/alda-lang/alda) server.

## Roles

- Responds to requests from an Alda client.
- Manages Alda worker processes.

## Development

### Overview

The Alda server handles a variety of things, including parsing Alda code into executable Clojure code, executing the code to create or modify a score, modeling a musical score as a Clojure map of data, and using the map of data as input to interpret the score (generating sound).

The Alda server spawns external *worker* processes and delegates most of the aforementioned work to those processes. The processes communicate back and forth via [ZeroMQ](https://zeromq.org) sockets. For more information about the use of ZeroMQ in Alda processes, see our [ZeroMQ Architecture](https://github.com/alda-lang/alda/blob/master/doc/zeromq-architecture.md) document.

### Prerequisites

Development on the Alda server and worker processes requires that you have the [Boot](http://boot-clj.com) build tool installed. This allows you to run the `test` and `dev` tasks to run tests and run the server/worker locally for development.

### `boot test` task

To run the integration test suite, run `boot test`. This will test that the server and worker processes are communicating with each other correctly.

### `boot dev` task

The `dev` task allows you to run the Alda server and worker processes locally, including any changes you have made to the codebase.

Note that the Alda client only communicates with the server, never directly to the worker. This means that to test changes, you will often need to run both the server and the worker at the same time, and make sure the worker is using the correct port number to talk to the server backend. This setup is described in detail below.

#### Alda Server

To run an Alda server locally:

    $ boot dev -a server --port 27714 --alda-fingerprint

The `--port` and `--alda-fingerprint` arguments are strictly optional, but including them will ensure that the Alda client recognizes your development server as an Alda server and includes it in the output of `alda list`.

#### Alda Worker

To run an Alda worker process locally:

    $ boot dev -a worker --port 12345

The `--port` argument needs to be the backend port on which a server is managing its workers. You can see this in the output of the server when it starts up:

    $ boot dev -a server --port 27714 --alda-fingerprint
    ...
    16-Sep-04 15:21:49 skeggox.local INFO [alda.server] - Binding frontend socket on port 27714...
    16-Sep-04 15:21:49 skeggox.local INFO [alda.server] - Binding backend socket on port 60610...
    16-Sep-04 15:21:49 skeggox.local INFO [alda.server] - Spawning 4 workers...

    # in another terminal
    $ boot dev -a worker --port 60610 --alda-fingerprint
    ...
    16-Sep-04 15:23:15 skeggox.local INFO [alda.worker] - Logging errors to /Users/dave/.alda/logs/error.log
    Sep 04, 2016 3:23:15 PM com.jsyn.engine.SynthesisEngine start
    INFO: Pure Java JSyn from www.softsynth.com, rate = 44100, RT, V16.7.3 (build 457, 2014-12-25)

#### Tricking the Supervisor

The server has a "supervisor" routine that it does every so often to make sure that it still has the correct number of workers. If you start your own worker process in addition to the workers that the server spawned, then the server will have one more worker than it needs and will "lay off" one worker. The worker it lays off could be your debug worker, which could get in the way of what you're trying to do.

To prevent your debug worker process from being laid off, set the environment variable `ALDA_DISABLE_SUPERVISOR` when starting the server. You can also set the number of workers spawned by the server to 0 in order to ensure that your worker will receive all of the requests.

You may also want to set `ALDA_DEBUG_MODE` when starting the worker in order to see debug-level logs printed to the console, instead of only error logs logged to `~/.alda/logs/error.log` (instead of printed).

    $ ALDA_DISABLE_SUPERVISOR=yes boot dev -a server \
                                           --port 27714 \
                                           --workers 0 \
                                           --alda-fingerprint
    ...
    16-Sep-04 15:32:59 skeggox.local INFO [alda.server] - Binding frontend socket on port 27714...
    16-Sep-04 15:32:59 skeggox.local INFO [alda.server] - Binding backend socket on port 60830...
    16-Sep-04 15:32:59 skeggox.local INFO [alda.server] - Spawning 0 workers...

    # in a separate terminal
    $ ALDA_DEBUG_MODE=yes boot dev -a worker --port 60830 --alda-fingerprint
    ...
    16-Sep-04 15:34:55 skeggox.local INFO [alda.worker] - Loading Alda environment...
    Sep 04, 2016 3:34:55 PM com.jsyn.engine.SynthesisEngine start
    INFO: Pure Java JSyn from www.softsynth.com, rate = 44100, RT, V16.7.3 (build 457, 2014-12-25)
    16-Sep-04 15:34:58 skeggox.local INFO [alda.worker] - Worker reporting for duty!
    16-Sep-04 15:34:58 skeggox.local INFO [alda.worker] - Connecting to socket on port 60864...
    16-Sep-04 15:34:58 skeggox.local INFO [alda.worker] - Sending READY signal.
    16-Sep-04 15:34:58 skeggox.local DEBUG [alda.worker] - Got HEARTBEAT from server.
    16-Sep-04 15:34:59 skeggox.local DEBUG [alda.worker] - Got HEARTBEAT from server.
    16-Sep-04 15:35:00 skeggox.local DEBUG [alda.worker] - Got HEARTBEAT from server.


## License

Copyright © 2016 Dave Yarwood et al

Distributed under the Eclipse Public License version 1.0.
