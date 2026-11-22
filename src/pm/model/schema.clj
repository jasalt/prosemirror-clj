(ns pm.model.schema
  (:require [clojure.string :as str]
            [pm.model.content :as content]
            [pm.model.fragment :as fragment]
            [pm.model.mark :as mark]))

;; Identity-bearing, safely printable indirection for the schema construction cycle.
(deftype SchemaRef [state]
  clojure.lang.IDeref
  (deref [_] @state))
(defmethod print-method SchemaRef [_ writer] (.write ^java.io.Writer writer "#<schema>"))

(defn gather-marks [schema expr]
  (vec (mapcat
        (fn [name]
          (let [found (or (when-let [m (get-in schema [:marks name])] [m])
                          (seq (filter #(or (= name "_") (some #{name} (:groups %)))
                                       (sort-by :rank (vals (:marks schema))))))]
            (when-not found (throw (ex-info (str "Unknown mark type: " name) {})))
            found))
        (remove str/blank? (str/split expr #" +")))))

(defn schema [{:keys [nodes marks] :as spec}]
  (let [state (atom nil) registry (SchemaRef. state)
        types (into {} (map-indexed
                        (fn [rank [name spec]]
                          [name {:name name :spec spec :rank rank :registry registry
                                 :inline? (boolean (or (= name "text") (:inline spec)))
                                 :groups (str/split (or (:group spec) "") #" +")}]) nodes))
        ;; Keep declaration order even beyond Clojure's array-map threshold.
        types (into (sorted-map-by #(compare [(get-in types [%1 :rank] Long/MAX_VALUE) %1]
                                                     [(get-in types [%2 :rank] Long/MAX_VALUE) %2])) types)
        mark-types (into {} (map-indexed
                            (fn [rank [name spec]]
                              [name {:name name :spec spec :rank rank :registry registry
                                     :groups (str/split (or (:group spec) "") #" +")}]) marks))
        result {:nodes types :marks mark-types :spec spec}]
    (when-not (get types (or (:topNode spec) "doc"))
      (throw (ex-info "Schema is missing its top node type" {})))
    (when-not (get types "text") (throw (ex-info "Every schema needs a text type" {})))
    (when (seq (get-in types ["text" :spec :attrs]))
      (throw (ex-info "The text node type should not have attributes" {})))
    (reset! state result)
    (doseq [[name t] types]
      (when (get mark-types name) (throw (ex-info "Name cannot be both a node and a mark" {})))
      (content/parse (or (get-in t [:spec :content]) "") types)
      (when-let [expr (get-in t [:spec :marks])] (gather-marks result expr)))
    (doseq [[_ t] mark-types]
      (when-let [expr (get-in t [:spec :excludes])] (gather-marks result expr)))
    result))

(defn validate-attr [type k spec value]
  (when-let [validate (:validate spec)]
    (if (fn? validate) (validate value)
        (let [actual (cond (nil? value) "null" (number? value) "number"
                           (string? value) "string" (boolean? value) "boolean" :else "object")]
          (when-not (some #{actual} (str/split validate #"\|"))
            (throw (ex-info (str "Expected value of type " validate " for attribute " (name k)
                                 " on type " (:name type) ", got " actual) {})))))))

(defn compute-attrs [type attrs]
  (into {} (for [[k spec] (get-in type [:spec :attrs])]
             [k (cond (contains? attrs k) (do (validate-attr type k spec (get attrs k)) (get attrs k))
                      (contains? spec :default) (:default spec)
                      :else (throw (ex-info (str "No value supplied for attribute " (name k)) {})))])))

(defn mark
  ([schema name] (mark schema name nil))
  ([schema name attrs]
   (let [type (get-in schema [:marks name])]
     (when-not type (throw (ex-info (str "Unknown mark type: " name) {})))
     {:type type :attrs (compute-attrs type attrs)})))

(defn create
  ([type] (create type nil []))
  ([type attrs children] (create type attrs children nil))
  ([type attrs children marks]
   (when (= "text" (:name type)) (throw (ex-info "NodeType.create cannot construct text nodes" {})))
   {:type type :attrs (compute-attrs type attrs) :content (fragment/from children) :marks (mark/set-from marks)}))

(defn allows-marks? [type marks]
  (let [expr (get-in type [:spec :marks])
        m (content/parse (or (get-in type [:spec :content]) "") (:nodes @(:registry type)))]
    (or (= expr "_") (and (nil? expr) (content/inline-content? m))
        (let [allowed (set (gather-marks @(:registry type) (or expr "")))]
          (every? #(contains? allowed (:type %)) marks)))))

(defn valid-content? [type children]
  (let [m (content/parse (or (get-in type [:spec :content]) "") (:nodes @(:registry type)))
        end (content/match-fragment m children)]
    (and end (content/valid-end? end) (every? #(allows-marks? type (:marks %)) children))))

(defn create-checked
  ([type] (create-checked type nil []))
  ([type attrs children] (create-checked type attrs children nil))
  ([type attrs children marks]
   (let [node (create type attrs children marks)]
     (when-not (valid-content? type (:content node))
       (throw (ex-info (str "Invalid content for node " (:name type)) {})))
     node)))

(defn text
  ([schema s] (text schema s nil))
  ([schema s marks]
   (when-not (and (string? s) (seq s)) (throw (ex-info "Empty or invalid text node" {})))
   {:type (get-in schema [:nodes "text"]) :attrs {} :text s :content [] :marks (mark/set-from marks)}))

(defn create-and-fill [type]
  (let [m (content/parse (or (get-in type [:spec :content]) "") (:nodes @(:registry type)))
        children (content/fill-before m [] true)]
    (when children (create type nil children))))
