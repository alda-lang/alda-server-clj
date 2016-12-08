# CHANGELOG

## 0.1.2 (12/7/16)

* Fixed a regression introduced by 0.1.1. There was a bug causing worker processes not to cycle after suspending the process, e.g. closing and later re-opening your laptop's lid.

## 0.1.1 (12/3/16)

* Refactored JeroMQ code to use [ezzmq](https://github.com/daveyarwood/ezzmq).

* Minor bugfix in shutdown code: in some cases if the timing was just right, a java.util.concurrent.RejectedExecutionException was being thrown. The cause of this was the `blacklist-worker!` function trying to schedule the blacklist removal for after the server was shut down.

## 0.1.0 (11/19/16)

* alda-server-clj extracted from the Alda main project, version 1.0.0-rc50.
