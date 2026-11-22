(ns pm.transform.mapping-test
  "Translated from prosemirror-transform test/test-mapping.ts."
  (:require [clojure.test :refer [deftest is are]]
            [pm.transform.map :as m]))

(defn mk [& args]
  (reduce (fn [mapping arg]
            (if (map? arg)
              (reduce-kv (fn [mp from to] (m/set-mirror mp from to)) mapping (into (sorted-map) arg))
              (m/append-map mapping (m/step-map arg))))
          (m/mapping) args))

(defn test-mapping [mapping & cases]
  (let [inverted (m/invert mapping)]
    (doseq [[from to bias lossy] cases
            :let [bias (or bias 1)]]
      (is (= to (m/map-pos mapping from bias)) (str "map " from " bias " bias))
      (when-not lossy
        (is (= from (m/map-pos inverted to bias)) (str "inverse " to " bias " bias))))))

(defn flags [mapping pos side]
  (let [r (m/map-result mapping pos side)]
    (str (when (m/deleted? r) "d") (when (m/deleted-before? r) "b")
         (when (m/deleted-after? r) "a") (when (m/deleted-across? r) "x"))))

(deftest single-insertion
  (test-mapping (mk [2 0 4]) [0 0] [2 6] [2 2 -1] [3 7]))

(deftest single-deletion
  (test-mapping (mk [2 4 0]) [0 0] [2 2 -1] [3 2 1 true] [6 2 1] [6 2 -1 true] [7 3]))

(deftest single-replace
  (test-mapping (mk [2 4 4]) [0 0] [2 2 1] [4 6 1 true] [4 2 -1 true] [6 6 -1] [8 8]))

(deftest mirrored-delete-insert
  (test-mapping (mk [2 4 0] [2 0 4] {0 1}) [0 0] [2 2] [4 4] [6 6] [7 7]))

(deftest mirrored-insert-delete
  (test-mapping (mk [2 0 4] [2 4 0] {0 1}) [0 0] [2 2] [3 3]))

(deftest delete-insert-with-insert-between
  (test-mapping (mk [2 4 0] [1 0 1] [3 0 4] {0 2}) [0 0] [1 2] [4 5] [6 7] [7 8]))

(deftest deleted-flags
  (are [mapping pos side expected] (= expected (flags mapping pos side))
    ;; before
    (mk [0 2 0]) 2 -1 "db"
    (mk [0 2 0]) 2 1 "b"
    (mk [0 2 2]) 2 -1 "db"
    (mk [0 1 0] [0 1 0]) 2 -1 "db"
    (mk [0 1 0]) 2 -1 ""
    ;; after
    (mk [2 2 0]) 2 -1 "a"
    (mk [2 2 0]) 2 1 "da"
    (mk [2 2 2]) 2 1 "da"
    (mk [2 1 0] [2 1 0]) 2 1 "da"
    (mk [3 2 0]) 2 -1 ""
    ;; across
    (mk [0 4 0]) 2 -1 "dbax"
    (mk [0 4 0]) 2 1 "dbax"
    (mk [0 1 0] [4 1 0] [0 3 0]) 2 1 "dbax"
    ;; around
    (mk [4 1 0] [0 1 0]) 2 -1 ""
    (mk [2 1 0] [0 2 0]) 2 -1 "dba"
    (mk [2 1 0] [0 1 0]) 2 -1 "a"
    (mk [3 1 0] [0 2 0]) 2 -1 "db"))

(deftest no-deleted-flags-for-insertions
  (let [mp (mk [4 0 2])]
    (is (zero? (:del-info (m/map-result mp 4 -1))))
    (is (zero? (:del-info (m/map-result mp 4 1))))))

(deftest slicing
  (let [mp (mk [0 0 1] [5 2 0] [1 0 4])]
    (is (= 2 (m/map-pos (m/slice mp 1 2) 2)))
    (is (= 6 (m/map-pos (m/slice mp 0 2) 7)))
    (is (= 12 (m/map-pos (m/slice mp 1 3) 10)))))

(deftest append-sliced
  (let [mp (m/append-mapping (mk [3 1 2]) (m/slice (mk [0 0 1] [5 2 0] [1 0 4]) 1 2))]
    (is (= 2 (count (:maps mp))))
    (is (= 6 (m/map-pos mp 7)))))

(deftest mirror-info-preserved-on-append
  (let [mp (m/append-mapping (mk [3 1 2] [10 1 0])
                             (m/slice (mk [0 0 1] [0 1 0] [3 1 0] [3 0 1] {0 1 2 3}) 1))]
    (is (nil? (m/get-mirror mp 2)))
    (is (= 4 (m/get-mirror mp 3)))))

(deftest invert-sliced
  (let [mp (mk [0 0 1] [5 2 0] [1 0 4])]
    (is (= 12 (m/map-pos (m/invert (m/slice mp 1 2)) 10)))
    (is (= 11 (m/map-pos (m/invert (m/slice mp 0 2)) 10)))
    (is (= 8 (m/map-pos (m/invert (m/slice mp 1 3)) 10)))))

(deftest step-map-basics
  (is (= [] (:ranges (m/step-map []))))
  (is (= 5 (m/map-pos (m/step-map [0 0 3]) 2)))
  (is (= 2 (m/map-pos (m/invert-map (m/step-map [0 0 3])) 5)))
  (is (= "-[0,0,3]" (m/step-map-to-string (m/invert-map (m/step-map [0 0 3]))))))
