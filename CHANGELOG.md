# CHANGELOG

## 0.1.7 (2017-05-19)

* Each worker process now has a lifespan of 15-20 minutes. Once its lifespan is
  up, it finishes doing whatever work it might be doing and then shuts down so
  that the server can replace it with a fresh worker.

  This ensures that available workers are always fresh (spawned within the last
  20 minutes), which helps us avoid audio errors like delayed MIDI audio.

  Fixes issue [#5](https://github.com/alda-lang/alda-server-clj/issues/5).

## 0.1.6 (2017-05-18)

* Improved the reliability of including the score map in `play-status` responses.

  Prior to this version, we were storing the state of a worker in three separate
  (isolated) atoms: `current-status`, `current-score`, and `current-error`.
  Because we were storing state this way, it was possible for one state to be
  updated independently of another, when we actually wanted to update them at
  the same time as part of a transaction. For example, the status could be
  updated to "playing," and then the worker could answer a request for status
  before it updated the score to the score it responds with, so the status and
  score could be out of sync.

  Clojure provides the `ref` type to solve problems like this; they allow you to
  update multiple values transactionally. As of this version, we are using refs
  instead of atoms, which should make the `play-status` responses more
  consistent.

## 0.1.5 (2017-05-17)

* Include the score map (as a JSON string) in response to `play-status`
  requests, if the score has been parsed at that point.

  This will enable the upcoming client-side (Java) Alda REPL to determine the
  current instruments of the score and display them in the REPL prompt.

## 0.1.4 (2017-02-19)

* Fixed a regression introduced in 0.1.3 that was causing the `from` and `to` options not to have an effect.

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
