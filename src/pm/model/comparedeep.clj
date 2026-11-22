(ns pm.model.comparedeep)

(defn compare-deep [a b]
  (cond
    (and (number? a) (number? b)) (== a b)
    (and (map? a) (map? b))
    (and (= (set (keys a)) (set (keys b)))
         (every? #(compare-deep (get a %) (get b %)) (keys a)))
    (and (sequential? a) (sequential? b))
    (and (= (count a) (count b)) (every? true? (map compare-deep a b)))
    :else (= a b)))
