(ns alda.queue)

(defn queue
  "A janky custom data structure that acts like a queue and is a ref.

   It's really just a vector wrapped in a ref.

   Items are popped from the left and pushed onto the right."
  ([]
   (ref []))
  ([init]
   (ref (into [] init))))

(defn push-queue
  [q x]
  (alter q #(conj % x)))

(defn pop-queue
  "Pops off the first item in the queue and returns it."
  [q]
  (let [x (first @q)]
    (alter q #(vec (drop 1 %)))
    x))

(defn reverse-pop-queue
  "Pops off the LAST item in the queue and returns it."
  [q]
  (let [x (last @q)]
    (alter q #(vec (drop-last 1 %)))
    x))

(defn check-queue
  "Returns true if there is at least one item in the queue that satisfies the
   predicate."
  [q pred]
  (boolean (some pred @q)))

(defn re-queue
  "Finds all items in the queue that satisfy the predicate, and re-queues them
   onto the end of the queue.

   When a second function `f` is provided, it is called on each re-queued
   element. This can be used e.g. to update the timestamps of queued items."
  [q pred & [f]]
  (alter q #(let [yes (for [x % :when (pred x)]
                        ((or f identity) x))
                  no  (filter (complement pred) %)]
              (vec (concat no yes)))))

(defn remove-from-queue
  "Removes all items from the queue that satisfy the predicate."
  [q pred]
  (alter q #(vec (filter (complement pred) %))))

