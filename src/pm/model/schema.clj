(ns pm.model.schema
  (:require [clojure.string :as str]
            [pm.model.content :as content]))

(defn schema [{:keys [nodes marks] :as spec}]
  (let [registry (atom nil)
        types (into {} (map-indexed
                        (fn [rank [name spec]]
                          [name {:name name :spec spec :rank rank :registry registry
                                 :inline? (boolean (or (= name "text") (:inline spec)))
                                 :groups (str/split (or (:group spec) "") #" +")}]) nodes))
        ;; Keep declaration order even beyond Clojure's array-map threshold.
        types (into (sorted-map-by #(compare (:rank (get types %1)) (:rank (get types %2)))) types)
        mark-types (into {} (map-indexed
                            (fn [rank [name spec]]
                              [name {:name name :spec spec :rank rank :registry registry
                                     :groups (str/split (or (:group spec) "") #" +")}]) marks))
        result {:nodes types :marks mark-types :spec spec}]
    (reset! registry result)
    (doseq [[_ t] types] (content/parse (or (get-in t [:spec :content]) "") types))
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
  ([type attrs children]
   {:type type :attrs (compute-attrs type attrs) :content (vec children) :marks []}))

(defn text [schema s]
  (when (empty? s) (throw (ex-info "Empty text nodes are not allowed" {})))
  (assoc (create (get-in schema [:nodes "text"])) :text s))

(defn create-and-fill [type]
  (let [m (content/parse (or (get-in type [:spec :content]) "") (:nodes @(:registry type)))
        children (content/fill-before m [] true)]
    (when children (create type nil children))))
