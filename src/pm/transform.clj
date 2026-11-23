(ns pm.transform
  "Port of prosemirror-transform's step.ts, replace_step.ts, mark_step.ts and
  attr_step.ts (all step classes live in this one namespace).

  A step is a map with a `:step` key naming its JSON stepType, e.g.
  `{:step \"replace\" :from 1 :to 2 :slice slice :structure? false}`.
  Every operation is a multimethod/function on that key.

  `apply-step` returns `{:doc doc :failed nil}` or `{:doc nil :failed msg}`
  for upstream's soft failures (StepResult); it throws for hard errors such as
  out-of-range positions or invalid attribute values, as upstream does.
  `apply-steps` is the server-side batch primitive: all-or-nothing, and it
  converts every exception into a failure carrying the offending index."
  (:require [pm.model.fragment :as fragment]
            [pm.model.mark :as mark]
            [pm.model.node :as node]
            [pm.model.replace :as replace]
            [pm.model.resolvedpos :as rp]
            [pm.model.schema :as schema]
            [pm.transform.map :as tmap]))

(defn- fail [message] (throw (ex-info message {})))

(defn- int-value
  "Differences from upstream: positions must be integral numbers (JS accepts any
  number and misbehaves on fractions). Integral doubles such as 3.0 are coerced."
  [x]
  (when (and (number? x) (not (and (float? x) (or (Double/isNaN x) (Double/isInfinite x))))
             (== x (Math/floor (double x))))
    (long x)))

;; ---- results ----
(defn ok [doc] {:doc doc :failed nil})
(defn failure [message] {:doc nil :failed message})
(defn from-replace [doc from to slice]
  (try (ok (replace/replace doc from to slice))
       (catch clojure.lang.ExceptionInfo e
         (if (= ::replace/replace-error (:type (ex-data e)))
           (failure (ex-message e))
           (throw e)))))

;; ---- constructors ----
(defn replace-step
  ([from to slice] (replace-step from to slice false))
  ([from to slice structure?] {:step "replace" :from from :to to :slice slice :structure? (boolean structure?)}))
(defn replace-around-step
  ([from to gap-from gap-to slice insert] (replace-around-step from to gap-from gap-to slice insert false))
  ([from to gap-from gap-to slice insert structure?]
   {:step "replaceAround" :from from :to to :gap-from gap-from :gap-to gap-to
    :slice slice :insert insert :structure? (boolean structure?)}))
(defn add-mark-step [from to mark] {:step "addMark" :from from :to to :mark mark})
(defn remove-mark-step [from to mark] {:step "removeMark" :from from :to to :mark mark})
(defn mark-set-step [steps] {:step "markSet" :steps (vec steps)})
(defn add-node-mark-step [pos mark] {:step "addNodeMark" :pos pos :mark mark})
(defn remove-node-mark-step [pos mark] {:step "removeNodeMark" :pos pos :mark mark})
(defn attr-step
  "With no value, the step carries no :value key: JS `undefined`, which makes
  the node fall back to the attribute default. A nil value is explicit null."
  ([pos attr] {:step "attr" :pos pos :attr attr})
  ([pos attr value] {:step "attr" :pos pos :attr attr :value value}))
(defn doc-attr-step
  ([attr] {:step "docAttr" :attr attr})
  ([attr value] {:step "docAttr" :attr attr :value value}))

(defmulti apply-step* (fn [step _doc] (:step step)))
(defmulti get-map :step)
(defmethod get-map :default [_] tmap/empty-step-map)
(defmulti invert-step (fn [step _doc] (:step step)))
(defmulti map-step (fn [step _mapping] (:step step)))
(defmulti merge-steps (fn [step _other] (:step step)))
(defmethod merge-steps :default [_ _] nil)
(defmulti step-to-json :step)
(defmulti step-from-json* (fn [_schema json] (:stepType json)))

(defn apply-step [step doc] (apply-step* step doc))

(defn step-from-json [schema json]
  (when-not (and (map? json) (:stepType json)) (fail "Invalid input for Step.fromJSON"))
  (when-not (contains? (methods step-from-json*) (:stepType json))
    (fail (str "No step type " (:stepType json) " defined")))
  (step-from-json* schema json))

(defn apply-steps
  "Apply steps in order to doc. Returns `{:doc doc :failed nil}` or
  `{:doc nil :failed message :index i}`. Never throws."
  [steps doc]
  (loop [doc doc i 0 steps (seq steps)]
    (if-not steps
      (ok doc)
      (let [res (try (apply-step (first steps) doc)
                     (catch Exception e (failure (or (ex-message e) (str (class e))))))]
        (if (:doc res)
          (recur (:doc res) (inc i) (next steps))
          (assoc res :index i))))))

;; ---- replace ----
(defn- content-between [doc from to]
  (let [$from (rp/resolve doc from)
        [depth dist] (loop [depth (:depth $from) dist (- to from)]
                       (if (and (pos? dist) (pos? depth)
                                (= (rp/index-after $from depth) (count (:content (rp/node $from depth)))))
                         (recur (dec depth) (dec dist))
                         [depth dist]))]
    (boolean
     (when (pos? dist)
       (loop [next (fragment/maybe-child (:content (rp/node $from depth)) (rp/index-after $from depth))
              dist dist]
         (cond (not (pos? dist)) false
               (or (nil? next) (fragment/is-leaf? next)) true
               :else (recur (first (:content next)) (dec dist))))))))

(defn- slice-size [step] (replace/slice-size (:slice step)))

(defmethod apply-step* "replace" [{:keys [from to slice structure?]} doc]
  (if (and structure? (content-between doc from to))
    (failure "Structure replace would overwrite content")
    (from-replace doc from to slice)))
(defmethod get-map "replace" [{:keys [from to] :as step}]
  (tmap/step-map [from (- to from) (slice-size step)]))
(defmethod invert-step "replace" [{:keys [from to] :as step} doc]
  (replace-step from (+ from (slice-size step)) (replace/slice doc from to)))
(defmethod map-step "replace" [{:keys [from to slice structure?]} mapping]
  (let [to* (tmap/map-result mapping to -1) from* (tmap/map-result mapping from 1)]
    (when-not (and (tmap/deleted-across? from*) (tmap/deleted-across? to*))
      (replace-step (:pos from*) (max (:pos from*) (:pos to*)) slice structure?))))
(defmethod merge-steps "replace" [this other]
  (when (and (= "replace" (:step other)) (not (:structure? other)) (not (:structure? this)))
    (let [{:keys [from to slice]} this
          join (fn [a b os oe]
                 (if (zero? (+ (replace/slice-size a) (replace/slice-size b)))
                   replace/empty-slice
                   (replace/make-slice (fragment/append (:content a) (:content b)) os oe)))]
      (cond
        (and (= (+ from (slice-size this)) (:from other))
             (zero? (:open-end slice)) (zero? (:open-start (:slice other))))
        (replace-step from (+ to (- (:to other) (:from other)))
                      (join slice (:slice other) (:open-start slice) (:open-end (:slice other))) false)

        (and (= (:to other) from)
             (zero? (:open-start slice)) (zero? (:open-end (:slice other))))
        (replace-step (:from other) to
                      (join (:slice other) slice (:open-start (:slice other)) (:open-end slice)) false)))))
(defmethod step-to-json "replace" [{:keys [from to slice structure?]}]
  (cond-> {:stepType "replace" :from from :to to}
    (not (zero? (replace/slice-size slice))) (assoc :slice (replace/slice-to-json slice))
    structure? (assoc :structure true)))
(defmethod step-from-json* "replace" [schema json]
  (let [from (int-value (:from json)) to (int-value (:to json))]
    (when-not (and from to) (fail "Invalid input for ReplaceStep.fromJSON"))
    (replace-step from to (replace/slice-from-json schema (:slice json)) (boolean (:structure json)))))

;; ---- replaceAround ----
(defmethod apply-step* "replaceAround" [{:keys [from to gap-from gap-to slice insert structure?]} doc]
  (if (and structure? (or (content-between doc from gap-from) (content-between doc gap-to to)))
    (failure "Structure gap-replace would overwrite content")
    (let [gap (replace/slice doc gap-from gap-to)]
      (if (or (pos? (:open-start gap)) (pos? (:open-end gap)))
        (failure "Gap is not a flat range")
        (if-let [inserted (replace/insert-at slice insert (:content gap))]
          (from-replace doc from to inserted)
          (failure "Content does not fit in gap"))))))
(defmethod get-map "replaceAround" [{:keys [from to gap-from gap-to insert] :as step}]
  (tmap/step-map [from (- gap-from from) insert gap-to (- to gap-to) (- (slice-size step) insert)]))
(defmethod invert-step "replaceAround" [{:keys [from gap-from gap-to insert structure?] :as step} doc]
  (let [gap (- gap-to gap-from)]
    (replace-around-step from (+ from (slice-size step) gap)
                         (+ from insert) (+ from insert gap)
                         (replace/remove-between (replace/slice doc from (:to step))
                                                 (- gap-from from) (- gap-to from))
                         (- gap-from from) structure?)))
(defmethod map-step "replaceAround" [{:keys [from to gap-from gap-to slice insert structure?]} mapping]
  (let [from* (tmap/map-result mapping from 1) to* (tmap/map-result mapping to -1)
        gap-from* (if (= from gap-from) (:pos from*) (tmap/map-pos mapping gap-from -1))
        gap-to* (if (= to gap-to) (:pos to*) (tmap/map-pos mapping gap-to 1))]
    (when-not (or (and (tmap/deleted-across? from*) (tmap/deleted-across? to*))
                  (< gap-from* (:pos from*)) (> gap-to* (:pos to*)) (> gap-from* gap-to*))
      (replace-around-step (:pos from*) (:pos to*) gap-from* gap-to* slice insert structure?))))
(defmethod step-to-json "replaceAround" [{:keys [from to gap-from gap-to insert slice structure?]}]
  (cond-> {:stepType "replaceAround" :from from :to to :gapFrom gap-from :gapTo gap-to :insert insert}
    (not (zero? (replace/slice-size slice))) (assoc :slice (replace/slice-to-json slice))
    structure? (assoc :structure true)))
(defmethod step-from-json* "replaceAround" [schema json]
  (let [[from to gap-from gap-to insert] (map #(int-value (get json %)) [:from :to :gapFrom :gapTo :insert])]
    (when-not (and from to gap-from gap-to insert) (fail "Invalid input for ReplaceAroundStep.fromJSON"))
    (replace-around-step from to gap-from gap-to (replace/slice-from-json schema (:slice json))
                         insert (boolean (:structure json)))))

;; ---- marks over ranges ----
(defn- map-fragment [content f parent]
  (fragment/from-array
   (mapv (fn [i]
           (let [child (nth content i)
                 child (if (pos? (fragment/size (:content child)))
                         (node/copy child (map-fragment (:content child) f child))
                         child)]
             (if (get-in child [:type :inline?]) (f child parent) child)))
         (range (count content)))))

(defn- with-marks [n marks] (assoc n :marks (vec marks)))
(defn- map-slice [slice f parent]
  (replace/make-slice (map-fragment (:content slice) f parent) (:open-start slice) (:open-end slice)))

(defmethod apply-step* "addMark" [{:keys [from to mark]} doc]
  (let [old (replace/slice doc from to)
        $from (rp/resolve doc from)
        parent (rp/node $from (rp/shared-depth $from to))
        slice (map-slice old
                         (fn [n parent]
                           (if (or (not (schema/is-atom? n))
                                   (not (schema/allows-mark-type? (:type parent) (:type mark))))
                             n
                             (with-marks n (mark/add-to-set mark (:marks n)))))
                         parent)]
    (from-replace doc from to slice)))

(defmethod apply-step* "removeMark" [{:keys [from to mark]} doc]
  (let [old (replace/slice doc from to)
        slice (map-slice old (fn [n _] (with-marks n (mark/remove-from-set mark (:marks n)))) doc)]
    (from-replace doc from to slice)))

(defn- push-mark-step [kind from to mark target]
  (let [last-step (peek target)]
    (if (and last-step (= kind (:step last-step)) (= (:to last-step) from) (mark/eq (:mark last-step) mark))
      (conj (pop target) (assoc last-step :to to))
      (conj target {:step kind :from from :to to :mark mark}))))

(defn- one-or-set [steps] (if (= 1 (count steps)) (first steps) (mark-set-step steps)))

(defmethod invert-step "addMark" [{:keys [from to mark]} doc]
  (let [steps (atom [])]
    (node/nodes-between doc from to
      (fn [n pos _ _]
        (when (and (get-in n [:type :inline?]) (schema/is-atom? n))
          (let [added (mark/add-to-set mark (:marks n))]
            (when-not (mark/same-set added (:marks n))
              (let [from* (max from pos) to* (min to (+ pos (fragment/node-size n)))]
                (swap! steps #(push-mark-step "removeMark" from* to* mark %))
                (doseq [m (:marks n) :when (not (mark/is-in-set m added))]
                  (swap! steps #(push-mark-step "addMark" from* to* m %)))))))
        nil))
    (one-or-set @steps)))

(defmethod invert-step "removeMark" [{:keys [from to mark]} doc]
  (let [steps (atom [])]
    (node/nodes-between doc from to
      (fn [n pos _ _]
        (when (and (get-in n [:type :inline?]) (schema/is-atom? n) (mark/is-in-set mark (:marks n)))
          (swap! steps #(push-mark-step "addMark" (max from pos) (min to (+ pos (fragment/node-size n))) mark %)))
        nil))
    (one-or-set @steps)))

(defn- map-range-mark-step [kind {:keys [from to mark]} mapping]
  (let [from* (tmap/map-pos mapping from 1) to* (tmap/map-pos mapping to -1)]
    (when (< from* to*) {:step kind :from from* :to to* :mark mark})))
(defmethod map-step "addMark" [step mapping] (map-range-mark-step "addMark" step mapping))
(defmethod map-step "removeMark" [step mapping] (map-range-mark-step "removeMark" step mapping))

(defn- merge-range-mark-step [kind this other]
  (when (and (= kind (:step other)) (mark/eq (:mark other) (:mark this))
             (<= (:from this) (:to other)) (>= (:to this) (:from other)))
    {:step kind :from (min (:from this) (:from other)) :to (max (:to this) (:to other)) :mark (:mark this)}))
(defmethod merge-steps "addMark" [this other] (merge-range-mark-step "addMark" this other))
(defmethod merge-steps "removeMark" [this other] (merge-range-mark-step "removeMark" this other))

(defmethod step-to-json "addMark" [{:keys [from to mark]}]
  {:stepType "addMark" :mark (mark/to-json mark) :from from :to to})
(defmethod step-to-json "removeMark" [{:keys [from to mark]}]
  {:stepType "removeMark" :mark (mark/to-json mark) :from from :to to})
(defn- range-mark-from-json [kind label schema json]
  (let [from (int-value (:from json)) to (int-value (:to json))]
    (when-not (and from to) (fail (str "Invalid input for " label ".fromJSON")))
    {:step kind :from from :to to :mark (mark/from-json schema (:mark json))}))
(defmethod step-from-json* "addMark" [schema json] (range-mark-from-json "addMark" "AddMarkStep" schema json))
(defmethod step-from-json* "removeMark" [schema json] (range-mark-from-json "removeMark" "RemoveMarkStep" schema json))

;; ---- markSet ----
(defmethod apply-step* "markSet" [{:keys [steps]} doc]
  (loop [doc doc steps (seq steps)]
    (if-not steps (ok doc)
      (let [res (apply-step (first steps) doc)]
        (if (:doc res) (recur (:doc res) (next steps)) res)))))
(defmethod invert-step "markSet" [{:keys [steps]} doc] (mark-set-step (map #(invert-step % doc) steps)))
(defmethod map-step "markSet" [{:keys [steps]} mapping]
  (let [mapped (keep #(map-step % mapping) steps)] (when (seq mapped) (mark-set-step mapped))))
(defmethod step-to-json "markSet" [{:keys [steps]}] {:stepType "markSet" :steps (mapv step-to-json steps)})
(defmethod step-from-json* "markSet" [schema json]
  (when-not (and (vector? (:steps json))
                 (every? #(and (map? %) (#{"addMark" "removeMark"} (:stepType %))) (:steps json)))
    (fail "Invalid JSON for MarkSetStep"))
  (mark-set-step (mapv #(step-from-json schema %) (:steps json))))

;; ---- node marks ----
(defn- replace-node [doc pos updated]
  (let [n (node/node-at doc pos)]
    (from-replace doc pos (inc pos)
                  (replace/make-slice [updated] 0 (if (fragment/is-leaf? n) 0 1)))))

(defmethod apply-step* "addNodeMark" [{:keys [pos mark]} doc]
  (if-let [n (node/node-at doc pos)]
    (replace-node doc pos (schema/create (:type n) (:attrs n) nil (mark/add-to-set mark (:marks n))))
    (failure "No node at mark step's position")))
(defmethod apply-step* "removeNodeMark" [{:keys [pos mark]} doc]
  (if-let [n (node/node-at doc pos)]
    (replace-node doc pos (schema/create (:type n) (:attrs n) nil (mark/remove-from-set mark (:marks n))))
    (failure "No node at mark step's position")))

(defmethod invert-step "addNodeMark" [{:keys [pos mark]} doc]
  (let [n (node/node-at doc pos)]
    (or (when n
          (let [new-set (mark/add-to-set mark (:marks n))]
            (when (= (count new-set) (count (:marks n)))
              (add-node-mark-step pos (or (first (remove #(mark/is-in-set % new-set) (:marks n))) mark)))))
        (remove-node-mark-step pos mark))))
(defmethod invert-step "removeNodeMark" [{:keys [pos mark] :as step} doc]
  (let [n (node/node-at doc pos)]
    (if (or (nil? n) (not (mark/is-in-set mark (:marks n)))) step (add-node-mark-step pos mark))))

(defmethod map-step "addNodeMark" [{:keys [pos mark]} mapping]
  (let [r (tmap/map-result mapping pos 1)] (when-not (tmap/deleted-after? r) (add-node-mark-step (:pos r) mark))))
(defmethod map-step "removeNodeMark" [{:keys [pos mark]} mapping]
  (let [r (tmap/map-result mapping pos 1)] (when-not (tmap/deleted-after? r) (remove-node-mark-step (:pos r) mark))))

(defmethod step-to-json "addNodeMark" [{:keys [pos mark]}] {:stepType "addNodeMark" :pos pos :mark (mark/to-json mark)})
(defmethod step-to-json "removeNodeMark" [{:keys [pos mark]}] {:stepType "removeNodeMark" :pos pos :mark (mark/to-json mark)})
(defmethod step-from-json* "addNodeMark" [schema json]
  (let [pos (int-value (:pos json))]
    (when-not pos (fail "Invalid input for AddNodeMarkStep.fromJSON"))
    (add-node-mark-step pos (mark/from-json schema (:mark json)))))
(defmethod step-from-json* "removeNodeMark" [schema json]
  (let [pos (int-value (:pos json))]
    (when-not pos (fail "Invalid input for RemoveNodeMarkStep.fromJSON"))
    (remove-node-mark-step pos (mark/from-json schema (:mark json)))))

;; ---- attributes ----
;; Upstream distinguishes an absent value (`undefined`) from null. Absent means
;; "use the default" in NodeType.create and is dropped by JSON.stringify. The
;; port keeps that distinction with the presence of the :value key.
(defn- set-attr [attrs {:keys [attr] :as step}]
  (if (contains? step :value)
    (assoc attrs (keyword attr) (:value step))
    (dissoc attrs (keyword attr))))
(defn- with-current-value [step attrs attr]
  (let [k (keyword attr)]
    (if (contains? attrs k) (assoc step :value (get attrs k)) (dissoc step :value))))
(defn- value-json [json step]
  (cond-> json (contains? step :value) (assoc :value (:value step))))
(defn- value-from-json [step json]
  (cond-> step (contains? json :value) (assoc :value (:value json))))

(defmethod apply-step* "attr" [{:keys [pos] :as step} doc]
  (if-let [n (node/node-at doc pos)]
    (replace-node doc pos (schema/create (:type n) (set-attr (:attrs n) step) nil (:marks n)))
    (failure "No node at attribute step's position")))
(defmethod invert-step "attr" [{:keys [pos attr]} doc]
  (let [n (node/node-at doc pos)]
    (when-not n (fail "No node at attribute step's position"))
    (with-current-value (attr-step pos attr) (:attrs n) attr)))
(defmethod map-step "attr" [{:keys [pos] :as step} mapping]
  (let [r (tmap/map-result mapping pos 1)] (when-not (tmap/deleted-after? r) (assoc step :pos (:pos r)))))
(defmethod step-to-json "attr" [{:keys [pos attr] :as step}]
  (value-json {:stepType "attr" :pos pos :attr attr} step))
(defmethod step-from-json* "attr" [_ json]
  (let [pos (int-value (:pos json))]
    (when-not (and pos (string? (:attr json))) (fail "Invalid input for AttrStep.fromJSON"))
    (value-from-json (attr-step pos (:attr json)) json)))

(defmethod apply-step* "docAttr" [step doc]
  (ok (schema/create (:type doc) (set-attr (:attrs doc) step) (:content doc) (:marks doc))))
(defmethod invert-step "docAttr" [{:keys [attr]} doc] (with-current-value (doc-attr-step attr) (:attrs doc) attr))
(defmethod map-step "docAttr" [step _] step)
(defmethod step-to-json "docAttr" [{:keys [attr] :as step}]
  (value-json {:stepType "docAttr" :attr attr} step))
(defmethod step-from-json* "docAttr" [_ json]
  (when-not (string? (:attr json)) (fail "Invalid input for DocAttrStep.fromJSON"))
  (value-from-json (doc-attr-step (:attr json)) json))
