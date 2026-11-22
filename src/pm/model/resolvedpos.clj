(ns pm.model.resolvedpos
  "Port of prosemirror-model src/resolvedpos.ts.

  A resolved position is a map `{:pos :path :parent-offset :depth :text-offset}`.
  `:path` is a vector of `[node index start-offset]` triples, one per depth.
  Unlike upstream there is no resolve cache (see README, Differences)."
  (:refer-clojure :exclude [max min resolve])
  (:require [clojure.string :as str]
            [pm.model.fragment :as fragment]
            [pm.model.mark :as mark]
            [pm.model.schema :as schema]
            [pm.model.content :as content]))

(defn- fail [message] (throw (ex-info message {})))

(defn resolve [doc pos]
  (when-not (and (number? pos) (<= 0 pos (fragment/size (:content doc))))
    (fail (str "Position " pos " out of range")))
  (loop [node doc parent-offset pos start 0 path []]
    (let [{:keys [index offset]} (fragment/find-index (:content node) parent-offset)
          rem (- parent-offset offset)
          path (conj path [node index (+ start offset)])
          child (when-not (zero? rem) (fragment/child (:content node) index))]
      (if (or (zero? rem) (:text child))
        {:pos pos :path path :parent-offset parent-offset :depth (dec (count path))
         :text-offset (- pos (nth (peek path) 2))}
        (recur child (dec rem) (+ start offset 1) path)))))

(defn resolve-depth [$pos depth]
  (cond (nil? depth) (:depth $pos)
        (neg? depth) (+ (:depth $pos) depth)
        :else depth))

(defn node
  ([$pos] (node $pos nil))
  ([$pos depth] (first (nth (:path $pos) (resolve-depth $pos depth)))))
(defn parent [$pos] (node $pos (:depth $pos)))
(defn doc [$pos] (node $pos 0))
(defn index
  ([$pos] (index $pos nil))
  ([$pos depth] (second (nth (:path $pos) (resolve-depth $pos depth)))))
(defn index-after
  ([$pos] (index-after $pos nil))
  ([$pos depth]
   (let [depth (resolve-depth $pos depth)]
     (+ (index $pos depth)
        (if (and (= depth (:depth $pos)) (zero? (:text-offset $pos))) 0 1)))))

(defn start
  ([$pos] (start $pos nil))
  ([$pos depth]
   (let [depth (resolve-depth $pos depth)]
     (if (zero? depth) 0 (inc (nth (nth (:path $pos) (dec depth)) 2))))))
(defn end
  ([$pos] (end $pos nil))
  ([$pos depth]
   (let [depth (resolve-depth $pos depth)]
     (+ (start $pos depth) (fragment/size (:content (node $pos depth)))))))
(defn before
  ([$pos] (before $pos nil))
  ([$pos depth]
   (let [depth (resolve-depth $pos depth)]
     (when (zero? depth) (fail "There is no position before the top-level node"))
     (if (= depth (inc (:depth $pos))) (:pos $pos) (nth (nth (:path $pos) (dec depth)) 2)))))
(defn after
  ([$pos] (after $pos nil))
  ([$pos depth]
   (let [depth (resolve-depth $pos depth)]
     (when (zero? depth) (fail "There is no position after the top-level node"))
     (if (= depth (inc (:depth $pos))) (:pos $pos)
         (+ (nth (nth (:path $pos) (dec depth)) 2)
            (fragment/node-size (node $pos depth)))))))

(defn node-after [$pos]
  (let [parent (parent $pos) index (index $pos (:depth $pos))]
    (when-not (= index (count (:content parent)))
      (let [child (fragment/child (:content parent) index) off (:text-offset $pos)]
        (if (zero? off) child ((requiring-resolve 'pm.model.node/cut) child off))))))
(defn node-before [$pos]
  (let [index (index $pos (:depth $pos)) off (:text-offset $pos)]
    (cond (pos? off) ((requiring-resolve 'pm.model.node/cut) (fragment/child (:content (parent $pos)) index) 0 off)
          (zero? index) nil
          :else (fragment/child (:content (parent $pos)) (dec index)))))

(defn pos-at-index
  ([$pos i] (pos-at-index $pos i nil))
  ([$pos i depth]
   (let [depth (resolve-depth $pos depth) n (node $pos depth)]
     (+ (if (zero? depth) 0 (inc (nth (nth (:path $pos) (dec depth)) 2)))
        (reduce + 0 (map fragment/node-size (subvec (:content n) 0 i)))))))

(defn- drop-non-inclusive [marks other]
  (reduce (fn [marks m]
            (if (and (false? (get-in m [:type :spec :inclusive]))
                     (or (nil? other) (not (mark/is-in-set m (:marks other)))))
              (mark/remove-from-set m marks)
              marks))
          marks marks))

(defn marks [$pos]
  (let [parent (parent $pos) index (index $pos)]
    (cond
      (zero? (fragment/size (:content parent))) mark/none
      (pos? (:text-offset $pos)) (:marks (fragment/child (:content parent) index))
      :else
      (let [before (fragment/maybe-child (:content parent) (dec index))
            after (fragment/maybe-child (:content parent) index)
            [main other] (if before [before after] [after before])]
        (drop-non-inclusive (:marks main) other)))))

(defn marks-across [$pos $end]
  (let [after (fragment/maybe-child (:content (parent $pos)) (index $pos))]
    (when (and after (get-in after [:type :inline?]))
      (drop-non-inclusive (:marks after)
                          (fragment/maybe-child (:content (parent $end)) (index $end))))))

(defn shared-depth [$pos pos]
  (or (some #(when (and (<= (start $pos %) pos) (>= (end $pos %) pos)) %)
            (range (:depth $pos) 0 -1))
      0))

(defn same-parent [$pos other]
  (= (- (:pos $pos) (:parent-offset $pos)) (- (:pos other) (:parent-offset other))))
(defn max [$pos other] (if (> (:pos other) (:pos $pos)) other $pos))
(defn min [$pos other] (if (< (:pos other) (:pos $pos)) other $pos))

(defn- inline-content? [n] (content/inline-content? (schema/content-match (:type n))))

(declare block-range)
(defn block-range
  ([$pos] (block-range $pos $pos nil))
  ([$pos other] (block-range $pos other nil))
  ([$pos other pred]
   (if (< (:pos other) (:pos $pos))
     (block-range other $pos)
     (let [top (- (:depth $pos)
                  (if (or (inline-content? (parent $pos)) (= (:pos $pos) (:pos other))) 1 0))]
       (some #(when (and (<= (:pos other) (end $pos %)) (or (nil? pred) (pred (node $pos %))))
                {:from $pos :to other :depth %})
             (range top -1 -1))))))

(defn range-start [r] (before (:from r) (inc (:depth r))))
(defn range-end [r] (after (:to r) (inc (:depth r))))
(defn range-parent [r] (node (:from r) (:depth r)))
(defn range-start-index [r] (index (:from r) (:depth r)))
(defn range-end-index [r] (index-after (:to r) (:depth r)))

(defn to-string [$pos]
  (str (str/join "/" (for [i (range 1 (inc (:depth $pos)))]
                       (str (get-in (node $pos i) [:type :name]) "_" (index $pos (dec i)))))
       ":" (:parent-offset $pos)))
