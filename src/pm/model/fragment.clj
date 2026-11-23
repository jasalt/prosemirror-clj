(ns pm.model.fragment
  "Fragments are persistent vectors. Node-dependent operations resolve lazily
  to break the upstream Node/Fragment module cycle."
  (:refer-clojure :exclude [empty])
  (:require [clojure.string :as str]
            [pm.model.mark :as mark]
            [pm.model.comparedeep :refer [compare-deep]]))

(def empty [])
(defn is-leaf? [node] (not (seq (get-in node [:type :spec :content]))))
(defn node-size [node]
  (if-let [text (:text node)] (count text)
      (if (is-leaf? node) 1 (+ 2 (reduce + 0 (map node-size (:content node)))))))
(defn size [fragment] (reduce + 0 (map node-size fragment)))
(defn same-markup [a b]
  (and (= (:type a) (:type b)) (compare-deep (:attrs a) (:attrs b))
       (mark/same-set (:marks a) (:marks b))))
(defn from-array [nodes]
  (reduce (fn [out n]
            (if (and (:text n) (:text (peek out)) (same-markup (peek out) n))
              (conj (pop out) (update (peek out) :text str (:text n)))
              (conj out n))) [] nodes))
(defn from [value]
  (cond (nil? value) [] (vector? value) (from-array value)
        (and (map? value) (:type value)) [value]
        :else (throw (ex-info "Cannot convert value to Fragment" {}))))
(defn append [a b] (from-array (into (vec a) b)))
(defn eq [a b]
  (and (= (count a) (count b))
       (every? true? (map (requiring-resolve 'pm.model.node/eq) a b))))
(defn child [fragment index]
  (or (get fragment index) (throw (ex-info (str "Index " index " out of range") {}))))
(defn maybe-child [fragment index] (get fragment index))
(defn cut
  ([fragment from] (cut fragment from (size fragment)))
  ([fragment from to]
   (loop [nodes (when (> to from) (seq fragment)) pos 0 out []]
     (if (or (>= pos to) (empty? nodes)) out
       (let [node (first nodes) end (+ pos (node-size node))]
         (recur (next nodes) end
                (if (<= end from) out
                  (conj out (if (and (>= pos from) (<= end to)) node
                              (let [offset (if (:text node) pos (inc pos))
                                    limit (if (:text node) (count (:text node)) (size (:content node)))]
                                ((requiring-resolve 'pm.model.node/cut) node
                                 (max 0 (- from offset)) (min limit (- to offset)))))))))))))
(defn cut-by-index [fragment from to] (subvec fragment from to))
(defn replace-child [fragment index node] (child fragment index) (assoc fragment index node))
(defn add-to-start [fragment node] (into [node] fragment))
(defn add-to-end [fragment node] (conj fragment node))
(defn find-index [fragment pos]
  (when (or (neg? pos) (> pos (size fragment)))
    (throw (ex-info (str "Position " pos " outside of fragment") {})))
  (loop [index 0 offset 0]
    (if (= offset pos) {:index index :offset offset}
      (let [end (+ offset (node-size (child fragment index)))]
        (if (> end pos) {:index index :offset offset} (recur (inc index) end))))))
(defn nodes-between [fragment from to f node-start parent]
  (loop [index 0 pos 0]
    (when (< pos to)
      (let [node (child fragment index) end (+ pos (node-size node))]
        (when (and (> end from) (not (false? (f node (+ node-start pos) parent index)))
                   (pos? (size (:content node))))
          (let [start (inc pos)]
            (nodes-between (:content node) (max 0 (- from start))
                           (min (size (:content node)) (- to start)) f (+ node-start start) node)))
        (recur (inc index) end)))))
(defn to-json [fragment]
  (when (seq fragment) (mapv (requiring-resolve 'pm.model.node/to-json) fragment)))
(defn from-json [schema value]
  (cond (not value) []
        (not (vector? value)) (throw (ex-info "Invalid input for Fragment.fromJSON" {}))
        :else (from-array (mapv #((requiring-resolve 'pm.model.node/from-json) schema %) value))))
(defn to-string [fragment]
  (str "<" (str/join ", " (map (requiring-resolve 'pm.model.node/to-string) fragment)) ">"))
