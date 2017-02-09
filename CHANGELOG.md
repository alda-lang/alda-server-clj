# CHANGELOG

## 0.1.3 (2017-02-09)

* Added support for `play` requests that have a `history` field for context.

  For example, given a request like this:

  ```json
  {
    "command": "play",
    "history": "bassoon: (tempo 200) c d e",
    "body":    "f g2"
  }
  ```

  The server will play just the two notes `f` and `g2`, on a bassoon at 200 bpm.

## 0.1.2 (2016-12-07)

* Fixed a regression introduced by 0.1.1. There was a bug causing worker processes not to cycle after suspending the process, e.g. closing and later re-opening your laptop's lid.

## 0.1.1 (2016-12-03)

* Refactored JeroMQ code to use [ezzmq](https://github.com/daveyarwood/ezzmq).

* Minor bugfix in shutdown code: in some cases if the timing was just right, a java.util.concurrent.RejectedExecutionException was being thrown. The cause of this was the `blacklist-worker!` function trying to schedule the blacklist removal for after the server was shut down.

## 0.1.0 (2016-11-19)

* alda-server-clj extracted from the Alda main project, version 1.0.0-rc50.
