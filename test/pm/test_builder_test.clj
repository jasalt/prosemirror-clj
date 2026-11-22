(ns pm.test-builder-test
  (:require [clojure.test :refer [deftest is]]
            [pm.test-builder :as b]))

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
