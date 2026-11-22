(ns pm.model.mark
  (:require [clojure.string :as str]
            [pm.model.comparedeep :refer [compare-deep]]))

(def none [])
(defn to-json [mark]
  (cond-> {:type (get-in mark [:type :name])}
    (seq (:attrs mark)) (assoc :attrs (:attrs mark))))
(defn from-json [schema json]
  (when-not (map? json) (throw (ex-info "Invalid input for Mark.fromJSON" {})))
  (when-not (get-in schema [:marks (:type json)])
    (throw (ex-info (str "There is no mark type " (:type json) " in this schema") {})))
  ((requiring-resolve 'pm.model.schema/mark) schema (:type json) (:attrs json)))
(defn eq [a b]
  (and (= (:type a) (:type b)) (compare-deep (:attrs a) (:attrs b))))
(defn same-set [a b]
  (and (= (count a) (count b)) (every? true? (map eq a b))))
(defn set-from [marks]
  (vec (sort-by (comp :rank :type) (cond (nil? marks) [] (map? marks) [marks] :else marks))))
(defn excludes? [type other]
  (let [expr (get-in type [:spec :excludes])]
    (if (nil? expr) (= type other)
        (boolean (some #(and (not (str/blank? %))
                            (or (= % "_") (= % (:name other))
                                (some #{%} (:groups other)))) (str/split expr #" +"))))))
(defn add-to-set [mark marks]
  (loop [remaining marks out [] placed? false]
    (if-let [other (first remaining)]
      (cond
        (eq mark other) marks
        (excludes? (:type mark) (:type other)) (recur (next remaining) out placed?)
        (excludes? (:type other) (:type mark)) marks
        :else (let [insert? (and (not placed?) (> (get-in other [:type :rank]) (get-in mark [:type :rank])))]
                (recur (next remaining) (conj (cond-> out insert? (conj mark)) other)
                       (or placed? insert?))))
      (cond-> out (not placed?) (conj mark)))))
(defn remove-from-set [mark marks]
  (if-let [i (first (keep-indexed #(when (eq mark %2) %1) marks))]
    (into (subvec (vec marks) 0 i) (subvec (vec marks) (inc i))) marks))
(defn is-in-set [mark marks] (boolean (some #(eq mark %) marks)))
