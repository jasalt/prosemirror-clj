(ns pm.model.node
  (:refer-clojure :exclude [descendants])
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [pm.model.schema :as schema]
            [pm.model.fragment :as fragment]
            [pm.model.content :as content]
            [pm.model.mark :as mark]
            [pm.model.comparedeep :refer [compare-deep]]))

(def node-size fragment/node-size)
(def is-leaf? fragment/is-leaf?)
(def same-markup fragment/same-markup)
(defn copy [node content] (assoc node :content (fragment/from content)))
(defn cut
  ([node from] (cut node from (if (:text node) (count (:text node)) (fragment/size (:content node)))))
  ([node from to]
   (if-let [text (:text node)]
     (let [text (subs text from to)]
       (when (empty? text) (throw (ex-info "Empty text nodes are not allowed" {})))
       (assoc node :text text))
     (assoc node :content (fragment/cut (:content node) from to)))))
(defn nodes-between
  ([node from to f] (nodes-between node from to f 0))
  ([node from to f start] (fragment/nodes-between (:content node) from to f start node)))
(defn descendants [node f] (nodes-between node 0 (fragment/size (:content node)) f))
(defn node-at [node pos]
  (let [{:keys [index offset]} (fragment/find-index (:content node) pos)
        child (get (:content node) index)]
    (when child (if (or (= offset pos) (:text child)) child
                    (recur child (- pos offset 1))))))
(defn- textblock? [node]
  (and (not (get-in node [:type :inline?]))
       (content/inline-content?
        (content/parse (or (get-in node [:type :spec :content]) "")
                       (:nodes @(get-in node [:type :registry]))))))
(defn text-between
  ([node from to] (text-between node from to nil nil))
  ([node from to separator] (text-between node from to separator nil))
  ([node from to separator leaf-text]
   (if-let [text (:text node)] (subs text from (min to (count text)))
     (let [out (StringBuilder.) first-block? (atom true)]
       (nodes-between node from to
         (fn [child pos _ _]
           (let [leaf (or leaf-text (get-in child [:type :spec :leafText]))
                 text (cond (:text child) (subs (:text child) (max 0 (- from pos))
                                               (min (count (:text child)) (- to pos)))
                            (and (is-leaf? child) leaf) (if (fn? leaf) (leaf child) leaf)
                            :else "")]
             (when (and (not (get-in child [:type :inline?])) separator
                        (or (and (is-leaf? child) (seq text)) (textblock? child)))
               (if @first-block? (reset! first-block? false) (.append out ^String separator)))
             (.append out ^String text))))
       (str out)))))
(defn text-content [node]
  (or (:text node)
      (when (is-leaf? node) (when-let [f (get-in node [:type :spec :leafText])] (f node)))
      (text-between node 0 (fragment/size (:content node)) "")))
(defn to-string [node]
  (if-let [f (get-in node [:type :spec :toDebugString])] (f node)
    (let [base (or (when (:text node) (json/write-str (:text node) :escape-unicode false :escape-slash false))
                   (str (get-in node [:type :name])
                        (when (seq (:content node))
                          (str "(" (str/join ", " (map to-string (:content node))) ")"))))]
      (reduce (fn [s m] (str (get-in m [:type :name]) "(" s ")")) base (reverse (:marks node))))))

(defn content-match-at [node index]
  (or (content/match-fragment (schema/content-match (:type node)) (:content node) 0 index)
      (throw (ex-info "Called contentMatchAt on a node with invalid content" {}))))

(defn can-replace
  ([node from to] (can-replace node from to [] 0 0))
  ([node from to replacement] (can-replace node from to replacement 0 (count replacement)))
  ([node from to replacement start end]
   (let [replacement (fragment/from replacement)
         one (content/match-fragment (content-match-at node from) replacement start end)
         two (when one (content/match-fragment one (:content node) to (count (:content node))))]
     (boolean (and two (content/valid-end? two)
                   (every? #(schema/allows-marks? (:type node) (:marks %))
                           (subvec replacement start end)))))))

(defn can-replace-with
  ([node from to type] (can-replace-with node from to type nil))
  ([node from to type marks]
   (if (and marks (not (schema/allows-marks? (:type node) marks))) false
       (let [start (content/match-type (content-match-at node from) type)
             end (when start (content/match-fragment start (:content node) to (count (:content node))))]
         (boolean (and end (content/valid-end? end)))))))

(defn can-append [node other]
  (if (seq (:content other))
    (can-replace node (count (:content node)) (count (:content node)) (:content other))
    (schema/compatible-content? (:type node) (:type other))))

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

(defn to-json [node]
  (cond-> {:type (get-in node [:type :name])}
    (seq (:attrs node)) (assoc :attrs (:attrs node))
    (seq (:content node)) (assoc :content (mapv to-json (:content node)))
    (seq (:marks node)) (assoc :marks (mapv mark/to-json (:marks node)))
    (:text node) (assoc :text (:text node))))

(declare from-json)
(defn read-json [schema text] (from-json schema (json/read-str text :key-fn keyword)))
(defn write-json [node] (json/write-str (to-json node)))

(defn from-json [schema json]
  (when-not (map? json) (throw (ex-info "Invalid input for Node.fromJSON" {})))
  (let [name (:type json) type (get-in schema [:nodes name])]
    (when-not type (throw (ex-info (str "Unknown node type: " name) {})))
    (doseq [k [:marks :content] :when (some? (get json k))]
      (when-not (vector? (get json k)) (throw (ex-info (str "Invalid " (clojure.core/name k) " data") {}))))
    (let [marks (mapv #(mark/from-json schema %) (:marks json))
          node (if (= name "text")
                 (do (when-not (string? (:text json)) (throw (ex-info "Invalid text node in JSON" {})))
                     (schema/text schema (:text json)))
                 (schema/create type (:attrs json) (mapv #(from-json schema %) (:content json))))]
      (assoc node :marks (mark/set-from marks)))))
