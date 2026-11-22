(ns pm.transform.map
  "Port of prosemirror-transform src/map.ts (StepMap, MapResult, Mapping).

  A step map is `{:ranges [start old-size new-size ...] :inverted? bool}`.
  A mapping is `{:maps [step-map ...] :mirror [a b a b ...] :from n :to n}`
  and is immutable; `append-map` and friends return new mappings.
  A map result is `{:pos :del-info :recover}`. Mirror and recover handling
  follow upstream exactly (recover values are `index + offset * 65536`).
  `touches` and `forEach` are not ported (unused by the authority)."
  (:refer-clojure :exclude [slice])
  (:require [clojure.string :as str]))

(def ^:private del-before 1)
(def ^:private del-after 2)
(def ^:private del-across 4)
(def ^:private del-side 8)

(defn- make-recover [index offset] (+ index (* offset 65536)))
(defn- recover-index [v] (bit-and v 0xffff))
(defn- recover-offset [v] (quot (- v (bit-and v 0xffff)) 65536))

(defn map-result* [pos del-info recover] {:pos pos :del-info del-info :recover recover})
(defn deleted? [r] (pos? (bit-and (:del-info r) del-side)))
(defn deleted-before? [r] (pos? (bit-and (:del-info r) (bit-or del-before del-across))))
(defn deleted-after? [r] (pos? (bit-and (:del-info r) (bit-or del-after del-across))))
(defn deleted-across? [r] (pos? (bit-and (:del-info r) del-across)))

;; ---- StepMap ----
(defn step-map
  ([ranges] (step-map ranges false))
  ([ranges inverted?] {:ranges (vec ranges) :inverted? (boolean inverted?)}))
(def empty-step-map (step-map []))
(defn offset-map [n]
  (cond (zero? n) empty-step-map (neg? n) (step-map [0 (- n) 0]) :else (step-map [0 0 n])))
(defn invert-map [sm] (step-map (:ranges sm) (not (:inverted? sm))))
(defn step-map-to-string [sm]
  (str (when (:inverted? sm) "-") "[" (str/join "," (:ranges sm)) "]"))

(defn recover [sm value]
  (let [index (recover-index value)
        diff (if (:inverted? sm) 0
                 (reduce + 0 (for [i (range index)]
                               (- (get-in sm [:ranges (+ (* i 3) 2)]) (get-in sm [:ranges (+ (* i 3) 1)]))))) ]
    (+ (get-in sm [:ranges (* index 3)]) diff (recover-offset value))))

(defn- step-map* [sm pos assoc simple?]
  (let [{:keys [ranges inverted?]} sm
        old-index (if inverted? 2 1) new-index (if inverted? 1 2) n (count ranges)]
    (loop [i 0 diff 0]
      (if (>= i n)
        (if simple? (+ pos diff) (map-result* (+ pos diff) 0 nil))
        (let [start (- (nth ranges i) (if inverted? diff 0))]
          (if (> start pos)
            (if simple? (+ pos diff) (map-result* (+ pos diff) 0 nil))
            (let [old-size (nth ranges (+ i old-index)) new-size (nth ranges (+ i new-index))
                  end (+ start old-size)]
              (if (<= pos end)
                (let [side (cond (zero? old-size) assoc (= pos start) -1 (= pos end) 1 :else assoc)
                      result (+ start diff (if (neg? side) 0 new-size))]
                  (if simple? result
                      (let [rec (if (= pos (if (neg? assoc) start end)) nil
                                    (make-recover (quot i 3) (- pos start)))
                            del (if (pos? old-size)
                                  (bit-or (cond (= pos start) del-after (= pos end) del-before :else del-across)
                                          (if (if (neg? assoc) (not= pos start) (not= pos end)) del-side 0))
                                  0)]
                        (map-result* result del rec))))
                (recur (+ i 3) (+ diff (- new-size old-size)))))))))))

;; ---- Mapping ----
(defn mapping
  ([] {:maps [] :mirror nil :from 0 :to 0})
  ([maps] {:maps (vec maps) :mirror nil :from 0 :to (count maps)}))

(defn slice
  ([mp] (slice mp 0 (count (:maps mp))))
  ([mp from] (slice mp from (count (:maps mp))))
  ([mp from to] (assoc mp :from from :to to)))

(defn get-mirror [mp n]
  (let [mirror (:mirror mp)]
    (loop [i 0]
      (when (< i (count mirror))
        (if (= (nth mirror i) n)
          (nth mirror (if (odd? i) (dec i) (inc i)))
          (recur (inc i)))))))

(defn set-mirror [mp n m] (update mp :mirror (fnil conj []) n m))

(defn append-map
  ([mp sm] (append-map mp sm nil))
  ([mp sm mirrors]
   (let [maps (conj (:maps mp) sm)
         mp (assoc mp :maps maps :to (count maps))]
     (if (some? mirrors) (set-mirror mp (dec (count maps)) mirrors) mp))))

(defn append-mapping [mp other]
  (let [start-size (count (:maps mp))]
    (reduce (fn [acc i]
              (let [mirr (get-mirror other i)
                    mirr (when (some? mirr)
                           (when (and (< mirr i) (>= mirr (:from other)))
                             (+ start-size (- mirr (:from other)))))]
                (append-map acc (nth (:maps other) i) mirr)))
            mp (range (:from other) (:to other)))))

(defn append-mapping-inverted [mp other]
  (let [total-size (+ (count (:maps mp)) (count (:maps other)))]
    (reduce (fn [acc i]
              (let [mirr (get-mirror other i)
                    mirr (when (some? mirr)
                           (when (and (> mirr i) (< mirr (:to other)))
                             (- total-size (- mirr (:from other)) 1)))]
                (append-map acc (invert-map (nth (:maps other) i)) mirr)))
            mp (range (dec (:to other)) (dec (:from other)) -1))))

(defn invert [mp] (append-mapping-inverted (mapping) mp))

(defn- mapping* [mp pos assoc simple?]
  (let [maps (:maps mp) to (:to mp)]
    (loop [i (:from mp) pos pos del-info 0]
      (if (>= i to)
        (if simple? pos (map-result* pos del-info nil))
        (let [result (step-map* (nth maps i) pos assoc false)
              corr (when (some? (:recover result)) (get-mirror mp i))]
          (if (and (some? corr) (> corr i) (< corr to))
            (recur (inc corr) (recover (nth maps corr) (:recover result)) del-info)
            (recur (inc i) (:pos result) (bit-or del-info (:del-info result)))))))))

(defn map-pos
  "Map a position through a step map or a mapping. `assoc` defaults to 1."
  ([m pos] (map-pos m pos 1))
  ([m pos assoc]
   (if (contains? m :ranges)
     (step-map* m pos assoc true)
     (if (:mirror m)
       (mapping* m pos assoc true)
       (reduce (fn [pos i] (step-map* (nth (:maps m) i) pos assoc true)) pos (range (:from m) (:to m)))))))

(defn map-result
  ([m pos] (map-result m pos 1))
  ([m pos assoc]
   (if (contains? m :ranges) (step-map* m pos assoc false) (mapping* m pos assoc false))))
