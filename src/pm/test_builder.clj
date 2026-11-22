(ns pm.test-builder
  (:require [pm.model.schema :as s]))

(def schema
  (s/schema
   {:nodes [["doc" {:content "block+"}]
            ["paragraph" {:content "inline*" :group "block"}]
            ["blockquote" {:content "block+" :group "block"}]
            ["horizontal_rule" {:group "block"}]
            ["heading" {:content "inline*" :group "block" :attrs {:level {:default 1 :validate "number"}}}]
            ["code_block" {:content "text*" :marks "" :group "block"}]
            ["text" {:group "inline"}]
            ["image" {:inline true :group "inline" :attrs {:src {:validate "string"} :alt {:default nil} :title {:default nil}}}]
            ["hard_break" {:inline true :group "inline"}]
            ["ordered_list" {:content "list_item+" :group "block" :attrs {:order {:default 1}}}]
            ["bullet_list" {:content "list_item+" :group "block"}]
            ["list_item" {:content "paragraph block*"}]]}))

(defn- node-size [n]
  (if-let [text (:text n)] (count text)
      (if (get-in n [:type :spec :content])
        (+ 2 (reduce + 0 (map node-size (:content n)))) 1)))

(defn- flatten-children [children f]
  (reduce
   (fn [{:keys [nodes tag pos]} child]
     (if (string? child)
       (let [matcher (re-matcher #"<(\w+)>" child)
             parsed (loop [at 0 out "" tags tag]
                      (if (.find matcher)
                        (let [out (str out (subs child at (.start matcher)))]
                          (recur (.end matcher) out (assoc tags (.group matcher 1) (+ pos (count out)))))
                        {:text (str out (subs child at)) :tag tags}))
             text (:text parsed)]
         {:nodes (cond-> nodes (seq text) (conj (f (s/text schema text))))
          :tag (:tag parsed) :pos (+ pos (count text))})
       (let [flat (:flat child)
             ns (mapv f (or flat [child]))
             tags (or (:tag child) (:tag (meta child)))
             offset (+ pos (if (or flat (:text child)) 0 1))]
         {:nodes (into nodes ns)
          :tag (reduce-kv #(assoc %1 %2 (+ offset %3)) tag (or tags {}))
          :pos (+ pos (reduce + 0 (map node-size ns)))})))
   {:nodes [] :tag {} :pos 0} children))

(defn- take-attrs [defaults children]
  (if (and (seq children)
           (or (nil? (first children))
               (and (map? (first children))
                    (not (:type (first children))) (not (:flat (first children))))))
    [(merge defaults (first children)) (rest children)]
    [defaults children]))

(defn- join-text [nodes]
  (reduce (fn [out n]
            (let [prev (peek out)]
              (if (and (:text n) (:text prev) (= (:marks prev) (:marks n)))
                (conj (pop out) (update prev :text str (:text n)))
                (conj out n)))) [] nodes))

(defn- builder [type attrs]
  (fn [& children]
    (let [[attrs children] (take-attrs attrs children)
          {:keys [nodes tag]} (flatten-children children identity)]
      (with-meta (s/create (get-in schema [:nodes type]) attrs (join-text nodes)) {:tag tag}))))

(defn- mark-builder [name rank]
  (fn [& children]
    (let [mark {:type {:name name :rank rank} :attrs {}}
          {:keys [nodes tag]} (flatten-children children
                               #(update % :marks (fn [marks]
                                                   (if (some #{mark} marks) marks
                                                       (vec (sort-by (comp :rank :type) (conj marks mark)))))))]
      {:flat nodes :tag tag})))

(def em (mark-builder "em" 1))
(def strong (mark-builder "strong" 2))
(def code (mark-builder "code" 3))
(def blockquote (builder "blockquote" nil))
(def li (builder "list_item" nil))
(def ul (builder "bullet_list" nil))
(def ol (builder "ordered_list" nil))

(def doc (builder "doc" nil))
(def p (builder "paragraph" nil))
(def pre (builder "code_block" nil))
(def h1 (builder "heading" {:level 1}))
(def hr (builder "horizontal_rule" nil))
(def br (builder "hard_break" nil))
(def img (builder "image" {:src "img.png"}))
