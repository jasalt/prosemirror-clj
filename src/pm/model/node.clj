(ns pm.model.node
  (:require [clojure.string :as str]
            [pm.model.schema :as schema]
            [pm.model.content :as content]
            [pm.model.mark :as mark]
            [pm.model.comparedeep :refer [compare-deep]]))

(defn eq [a b]
  (and (= (:type a) (:type b)) (compare-deep (:attrs a) (:attrs b))
       (mark/same-set (:marks a) (:marks b)) (= (:text a) (:text b))
       (= (count (:content a)) (count (:content b)))
       (every? true? (map eq (:content a) (:content b)))))

(defn- check-attrs [type attrs]
  (let [specs (get-in type [:spec :attrs])]
    (doseq [k (keys attrs)]
      (when-not (contains? specs k)
        (throw (ex-info (str "Unsupported attribute " (name k) " on type " (:name type)) {}))))
    (doseq [[k spec] specs] (schema/validate-attr type k spec (get attrs k)))))

(defn- allows-marks? [type child]
  (let [expr (get-in type [:spec :marks])
        m (content/parse (or (get-in type [:spec :content]) "") (:nodes @(:registry type)))]
    (or (= expr "_") (and (nil? expr) (content/inline-content? m))
        (every? (fn [mark]
                  (some #(and (not (str/blank? %))
                              (or (= % (get-in mark [:type :name]))
                                  (some #{%} (get-in mark [:type :groups]))))
                        (str/split (or expr "") #" +"))) (:marks child)))))

(defn check [node]
  (let [type (:type node)
        m (content/parse (or (get-in type [:spec :content]) "") (:nodes @(:registry type)))
        matched (content/match-fragment m (:content node))]
    (when-not (and matched (content/valid-end? matched)
                   (every? #(allows-marks? type %) (:content node)))
      (throw (ex-info (str "Invalid content for node " (:name type)) {})))
    (check-attrs type (:attrs node))
    (doseq [m (:marks node)] (check-attrs (:type m) (:attrs m)))
    (when-not (mark/same-set (:marks node) (reduce #(mark/add-to-set %2 %1) [] (:marks node)))
      (throw (ex-info "Invalid collection of marks" {})))
    (doseq [child (:content node)] (check child))
    nil))

(defn- mark-json [mark]
  (cond-> {:type (get-in mark [:type :name])}
    (seq (:attrs mark)) (assoc :attrs (:attrs mark))))

(defn to-json [node]
  (cond-> {:type (get-in node [:type :name])}
    (seq (:attrs node)) (assoc :attrs (:attrs node))
    (seq (:content node)) (assoc :content (mapv to-json (:content node)))
    (seq (:marks node)) (assoc :marks (mapv mark-json (:marks node)))
    (:text node) (assoc :text (:text node))))

(defn from-json [schema json]
  (when-not (map? json) (throw (ex-info "Invalid input for Node.fromJSON" {})))
  (let [name (:type json) type (get-in schema [:nodes name])]
    (when-not type (throw (ex-info (str "Unknown node type: " name) {})))
    (doseq [k [:marks :content] :when (some? (get json k))]
      (when-not (vector? (get json k)) (throw (ex-info (str "Invalid " (clojure.core/name k) " data") {}))))
    (let [marks (mapv #(schema/mark schema (:type %) (:attrs %)) (:marks json))
          node (if (= name "text")
                 (do (when-not (string? (:text json)) (throw (ex-info "Invalid text node in JSON" {})))
                     (schema/text schema (:text json)))
                 (schema/create type (:attrs json) (mapv #(from-json schema %) (:content json))))]
      (assoc node :marks (mark/set-from marks)))))
