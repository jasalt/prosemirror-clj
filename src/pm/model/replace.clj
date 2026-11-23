(ns pm.model.replace
  "Port of prosemirror-model src/replace.ts, plus Node.slice/Node.replace/
  Node.resolve from node.ts (they live here to avoid a namespace cycle).

  A slice is `{:content fragment :open-start n :open-end n}`."
  (:refer-clojure :exclude [replace])
  (:require [pm.model.fragment :as fragment]
            [pm.model.node :as node]
            [pm.model.resolvedpos :as rp]
            [pm.model.schema :as schema]))

(defn- replace-error [message]
  (throw (ex-info message {:type ::replace-error})))
(defn- range-error [message] (throw (ex-info message {})))

;; ---- Slice ----
(def empty-slice {:content [] :open-start 0 :open-end 0})
(defn make-slice [content open-start open-end]
  {:content content :open-start open-start :open-end open-end})
(defn slice-size [s]
  (- (fragment/size (:content s)) (:open-start s) (:open-end s)))
(defn slice-eq [a b]
  (and (fragment/eq (:content a) (:content b))
       (= (:open-start a) (:open-start b)) (= (:open-end a) (:open-end b))))
(defn slice-to-string [s]
  (str (fragment/to-string (:content s)) "(" (:open-start s) "," (:open-end s) ")"))
(defn slice-to-json [s]
  (when (pos? (fragment/size (:content s)))
    (cond-> {:content (fragment/to-json (:content s))}
      (pos? (:open-start s)) (assoc :openStart (:open-start s))
      (pos? (:open-end s)) (assoc :openEnd (:open-end s)))))
(defn- js-falsy? [x]
  (or (nil? x) (false? x) (= "" x) (and (number? x) (or (zero? x) (and (float? x) (Double/isNaN x))))))

(defn- open-depth
  "Upstream reads `json.openStart || 0` and requires a number. JSON numbers that
  are integral (1.0, 1e0) become longs so depth comparisons are exact.
  Differences from upstream: fractional depths are rejected (JS accepts them
  and fails later or computes garbage)."
  [x]
  (let [x (if (js-falsy? x) 0 x)]
    (when-not (and (number? x) (not (Double/isInfinite (double x))) (== x (Math/rint (double x))))
      (range-error "Invalid input for Slice.fromJSON"))
    (long x)))

(defn slice-from-json [schema json]
  (if-not json
    empty-slice
    (let [open-start (open-depth (:openStart json)) open-end (open-depth (:openEnd json))]
      (make-slice (fragment/from-json schema (:content json)) open-start open-end))))

(defn max-open
  ([fragment] (max-open fragment true))
  ([fragment open-isolating]
   (letfn [(depth [pick]
             (loop [n (pick fragment) d 0]
               (if (and n (not (fragment/is-leaf? n))
                        (or open-isolating (not (get-in n [:type :spec :isolating]))))
                 (recur (pick (:content n)) (inc d))
                 d)))]
     (make-slice fragment (depth first) (depth peek)))))

(defn- remove-range [content from to]
  (let [{:keys [index offset]} (fragment/find-index content from)
        child (fragment/maybe-child content index)
        {index-to :index offset-to :offset} (fragment/find-index content to)]
    (if (or (= offset from) (:text child))
      (do (when (and (not= offset-to to) (not (:text (fragment/child content index-to))))
            (range-error "Removing non-flat range"))
          (fragment/append (fragment/cut content 0 from) (fragment/cut content to)))
      (do (when (not= index index-to) (range-error "Removing non-flat range"))
          (fragment/replace-child
           content index
           (node/copy child (remove-range (:content child) (- from offset 1) (- to offset 1))))))))

(defn- insert-into [content dist insert open-start open-end parent]
  (let [{:keys [index offset]} (fragment/find-index content dist)
        child (fragment/maybe-child content index)]
    (if (or (= offset dist) (:text child))
      (when-not (and parent (<= open-start 0) (<= open-end 0)
                     (not (node/can-replace parent index index insert)))
        (fragment/append (fragment/append (fragment/cut content 0 dist) insert)
                         (fragment/cut content dist)))
      (when-let [inner (insert-into (:content child) (- dist offset 1) insert
                                    (if (zero? index) (dec open-start) 0)
                                    (if (= index (dec (count content))) (dec open-end) 0)
                                    child)]
        (fragment/replace-child content index (node/copy child inner))))))

(defn insert-at [s pos fragment]
  (when-let [content (insert-into (:content s) (+ pos (:open-start s)) fragment
                                  (inc (:open-start s)) (inc (:open-end s)) nil)]
    (make-slice content (:open-start s) (:open-end s))))

(defn remove-between [s from to]
  (make-slice (remove-range (:content s) (+ from (:open-start s)) (+ to (:open-start s)))
              (:open-start s) (:open-end s)))

;; ---- replace ----
(defn- close [n content]
  (when-not (schema/valid-content? (:type n) content)
    (replace-error (str "Invalid content for node " (get-in n [:type :name]))))
  (node/copy n content))

(defn- check-join [main sub]
  (when-not (schema/compatible-content? (:type sub) (:type main))
    (replace-error (str "Cannot join " (get-in sub [:type :name]) " onto " (get-in main [:type :name])))))

(defn- joinable [$before $after depth]
  (let [n (rp/node $before depth)]
    (check-join n (rp/node $after depth))
    n))

(defn- add-node [child target]
  (let [prev (peek target)]
    (if (and prev (:text child) (node/same-markup child prev))
      (conj (pop target) (assoc prev :text (str (:text prev) (:text child))))
      (conj target child))))

(defn- add-range [$start $end depth target]
  (let [n (rp/node (or $end $start) depth)
        end-index (if $end (rp/index $end depth) (count (:content n)))
        [target start-index]
        (if $start
          (let [si (rp/index $start depth)]
            (cond (> (:depth $start) depth) [target (inc si)]
                  (pos? (:text-offset $start)) [(add-node (rp/node-after $start) target) (inc si)]
                  :else [target si]))
          [target 0])
        target (reduce (fn [t i] (add-node (fragment/child (:content n) i) t))
                       target (range start-index end-index))]
    (if (and $end (= (:depth $end) depth) (pos? (:text-offset $end)))
      (add-node (rp/node-before $end) target)
      target)))

(declare replace-two-way)
(defn- replace-three-way [$from $start $end $to depth]
  (let [open-start (and (> (:depth $from) depth) (joinable $from $start (inc depth)))
        open-end (and (> (:depth $to) depth) (joinable $end $to (inc depth)))
        content (add-range nil $from depth [])
        content
        (if (and open-start open-end (= (rp/index $start depth) (rp/index $end depth)))
          (do (check-join open-start open-end)
              (add-node (close open-start (replace-three-way $from $start $end $to (inc depth))) content))
          (cond-> content
            open-start (->> (add-node (close open-start (replace-two-way $from $start (inc depth)))))
            true (as-> c (add-range $start $end depth c))
            open-end (->> (add-node (close open-end (replace-two-way $end $to (inc depth)))))))]
    (add-range $to nil depth content)))

(defn- replace-two-way [$from $to depth]
  (let [content (add-range nil $from depth [])
        content (if (> (:depth $from) depth)
                  (let [type (joinable $from $to (inc depth))]
                    (add-node (close type (replace-two-way $from $to (inc depth))) content))
                  content)]
    (add-range $to nil depth content)))

(defn- prepare-slice-for-replace [s $along]
  (let [extra (- (:depth $along) (:open-start s))
        parent (rp/node $along extra)
        n (reduce (fn [n i] (node/copy (rp/node $along i) [n]))
                  (node/copy parent (:content s))
                  (range (dec extra) -1 -1))]
    {:start (rp/resolve n (+ (:open-start s) extra))
     :end (rp/resolve n (- (fragment/size (:content n)) (:open-end s) extra))}))

(defn- replace-outer [$from $to s depth]
  (let [index (rp/index $from depth) n (rp/node $from depth)]
    (cond
      (and (= index (rp/index $to depth)) (< depth (- (:depth $from) (:open-start s))))
      (node/copy n (fragment/replace-child (:content n) index (replace-outer $from $to s (inc depth))))

      (zero? (fragment/size (:content s)))
      (close n (replace-two-way $from $to depth))

      (and (zero? (:open-start s)) (zero? (:open-end s))
           (= (:depth $from) depth) (= (:depth $to) depth))
      (let [parent (rp/parent $from) content (:content parent)]
        (close parent (-> (fragment/cut content 0 (:parent-offset $from))
                          (fragment/append (:content s))
                          (fragment/append (fragment/cut content (:parent-offset $to))))))

      :else
      (let [{:keys [start end]} (prepare-slice-for-replace s $from)]
        (close n (replace-three-way $from start end $to depth))))))

(defn replace-resolved [$from $to s]
  (when (> (:open-start s) (:depth $from))
    (replace-error "Inserted content deeper than insertion position"))
  (when (not= (- (:depth $from) (:open-start s)) (- (:depth $to) (:open-end s)))
    (replace-error "Inconsistent open depths"))
  (replace-outer $from $to s 0))

;; ---- Node methods ----
(defn replace [doc from to s]
  (replace-resolved (rp/resolve doc from) (rp/resolve doc to) s))

(defn slice
  ([doc from] (slice doc from (fragment/size (:content doc)) false))
  ([doc from to] (slice doc from to false))
  ([doc from to include-parents]
   (if (= from to)
     empty-slice
     (let [$from (rp/resolve doc from) $to (rp/resolve doc to)
           depth (if include-parents 0 (rp/shared-depth $from to))
           start (rp/start $from depth) n (rp/node $from depth)
           content (fragment/cut (:content n) (- (:pos $from) start) (- (:pos $to) start))]
       (make-slice content (- (:depth $from) depth) (- (:depth $to) depth))))))
