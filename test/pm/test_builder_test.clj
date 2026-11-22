(ns pm.test-builder-test
  (:require [clojure.test :refer [deftest is]]
            [pm.test-builder :as b]
            [pm.model.schema :as s]))

(deftest schema-driven-builders
  (let [schema (s/schema {:nodes [["doc" {:content "text*"}] ["text" {}]]
                          :marks [["link" {:attrs {:href {}}}]]})
        builders (b/builders schema {"a" {:markType "link" :href "foo"}})
        node ((get builders "doc") ((get builders "a") {:href "bar"} "x<t>"))]
    (is (identical? (get-in schema [:nodes "text"])
                    (get-in node [:content 0 :type])))
    (is (= "bar" (get-in node [:content 0 :marks 0 :attrs :href])))
    (is (= {"t" 1} (:tag (meta node))))))

(deftest leaf-shorthand-and-nested-link
  (is (= (b/p (b/br) "x") (b/p b/br "x")))
  (is (= "foo" (get-in (b/p (b/a "x")) [:content 0 :marks 0 :attrs :href])))
  ;; Upstream builder keeps the inner mark if replacing it wouldn't add a mark.
  (is (= "inner" (get-in (b/p (b/a {:href "outer"} (b/a {:href "inner"} "x")))
                         [:content 0 :marks 0 :attrs :href]))))

(deftest nested-utf16-tags
  (let [d (b/doc (b/p "😀<a>x") "<b>" (b/p "z<c>"))]
    (is (= {"a" 3 "b" 5 "c" 7} (:tag (meta d))))
    (is (= "😀x" (get-in d [:content 0 :content 0 :text])))))

(deftest mark-flattening
  (let [d (b/doc (b/p "a" (b/em "b<x>" (b/strong "c")) "d"))]
    (is (= {"x" 3} (:tag (meta d))))
    (is (= ["a" "b" "c" "d"] (mapv :text (get-in d [:content 0 :content]))))
    (is (= [["em"] ["em" "strong"]]
           (mapv #(mapv (comp :name :type) (:marks %))
                 (subvec (get-in d [:content 0 :content]) 1 3))))))

(deftest adjacent-text-and-attrs
  (is (= ["ab"] (mapv :text (:content (b/p "a" "b")))))
  (is (= 3 (get-in (b/h1 {:level 3} "hi") [:attrs :level]))))
