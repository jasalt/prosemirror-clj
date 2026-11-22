(ns pm.model.fragment-test
  (:require [clojure.test :refer [deftest is are]]
            [pm.model.fragment :as f]
            [pm.model.node :as n]
            [pm.model.schema :as s]
            [pm.test-builder :as b]))

(deftest persistent-operations
  (let [a (:content (b/p "😀ab")) b (:content (b/p "cd"))]
    (is (= 4 (f/size a)))
    (is (= "😀abcd" (:text (first (f/append a b)))))
    (is (= "😀" (:text (first (f/cut a 0 2)))))
    (is (= "ab" (:text (first (f/cut a 2)))))
    (is (= "😀ab" (:text (first a))))
    (are [pos expected] (= expected (f/find-index a pos))
      0 {:index 0 :offset 0}
      2 {:index 0 :offset 0}
      4 {:index 1 :offset 4})
    (is (thrown? clojure.lang.ExceptionInfo (f/find-index a 5)))
    (is (thrown? clojure.lang.ExceptionInfo (f/child a -1)))
    (is (nil? (f/maybe-child a 1)))
    (is (= b (f/replace-child a 0 (first b))))
    (is (= b (f/cut-by-index (f/add-to-end a (first b)) 1 2)))
    (is (= (into b a) (f/add-to-start a (first b))))))

(deftest traversal-pruning
  (let [d (b/doc (b/blockquote (b/p "hidden")) (b/p "visible")) seen (atom [])]
    (n/descendants d (fn [node _ parent index]
                       (is (identical? node (get (:content parent) index)))
                       (swap! seen conj (or (:text node) (get-in node [:type :name])))
                       (not= "blockquote" (get-in node [:type :name]))))
    (is (= ["blockquote" "paragraph" "visible"] @seen))))

(deftest json-normalizes-adjacent-text
  (is (= ["ab"] (mapv :text (:content (n/from-json b/schema
                  {:type "paragraph" :content [{:type "text" :text "a"} {:type "text" :text "b"}]}))))))

(deftest schema-identity
  (let [spec {:nodes [["doc" {:content "text*"}] ["text" {}]]}
        a (s/schema spec) b (s/schema spec)]
    (is (not (n/eq (s/text a "x") (s/text b "x"))))))
