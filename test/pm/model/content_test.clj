(ns pm.model.content-test
  (:require [clojure.test :refer [deftest are is]]
            [clojure.string :as str]
            [pm.model.content :as content]
            [pm.test-builder :as b]))

;; Translated from prosemirror-model 1.25.13 test/test-content.ts.
(deftest match-type-tables
  (are [expr names expected]
    (= expected
       (boolean
        (some-> (reduce content/match-type
                        (content/parse expr (:nodes b/schema))
                        (map (:nodes b/schema) (remove str/blank? (str/split names #" "))))
                content/valid-end?)))
    "" "" true
    "" "image" false
    "image*" "" true
    "image*" "image" true
    "image*" "image image image image" true
    "image*" "image text" false
    "inline*" "image text" true
    "inline*" "paragraph" false
    "(paragraph | heading)" "paragraph" true
    "(paragraph | heading)" "image" false
    "paragraph horizontal_rule paragraph" "paragraph horizontal_rule paragraph" true
    "paragraph horizontal_rule" "paragraph horizontal_rule paragraph" false
    "paragraph horizontal_rule paragraph" "paragraph horizontal_rule" false
    "paragraph horizontal_rule" "horizontal_rule paragraph horizontal_rule" false
    "heading paragraph*" "heading" true
    "heading paragraph*" "heading paragraph paragraph" true
    "heading paragraph+" "heading paragraph" true
    "heading paragraph+" "heading paragraph paragraph" true
    "heading paragraph+" "heading" false
    "heading paragraph+" "paragraph paragraph" false
    "image?" "image" true
    "image?" "" true
    "image?" "image image" false
    "(heading paragraph+)+" "heading paragraph heading paragraph paragraph" true
    "(heading paragraph+)+" "heading paragraph heading paragraph paragraph horizontal_rule" false
    "hard_break{2}" "hard_break hard_break" true
    "hard_break{2}" "hard_break" false
    "hard_break{2}" "hard_break hard_break hard_break" false
    "hard_break{2, 4}" "hard_break hard_break" true
    "hard_break{2, 4}" "hard_break hard_break hard_break hard_break" true
    "hard_break{2, 4}" "hard_break hard_break hard_break" true
    "hard_break{2, 4}" "hard_break" false
    "hard_break{2, 4}" "hard_break hard_break hard_break hard_break hard_break" false
    "hard_break{2, 4} text*" "hard_break hard_break image" false
    "hard_break{2, 4} image?" "hard_break hard_break image" true
    "hard_break{2,}" "hard_break hard_break" true
    "hard_break{2,}" "hard_break hard_break hard_break hard_break" true
    "hard_break{2,}" "hard_break" false
))

(defn get-match [expr] (content/parse expr (:nodes b/schema)))

(deftest fill-before-tables
  (are [expr before after expected]
    (= (:content expected)
       (content/fill-before
        (content/match-fragment (get-match expr) (:content before))
        (:content after) true))
    "paragraph horizontal_rule paragraph" (b/doc (b/p) (b/hr)) (b/doc (b/p)) (b/doc)
    "paragraph horizontal_rule paragraph" (b/doc (b/p)) (b/doc (b/p)) (b/doc (b/hr))
    "hard_break*" (b/p (b/br)) (b/p (b/br)) (b/p)
    "hard_break*" (b/p (b/br)) (b/p) (b/p)
    "hard_break*" (b/p) (b/p (b/br)) (b/p)
    "hard_break*" (b/p) (b/p) (b/p)
    "hard_break+" (b/p (b/br)) (b/p (b/br)) (b/p)
    "hard_break+" (b/p) (b/p) (b/p (b/br))
    "hard_break+" (b/p) (b/p (b/img)) nil
    "heading* paragraph*" (b/doc (b/h1)) (b/doc (b/p)) (b/doc)
    "heading* paragraph*" (b/doc (b/h1)) (b/doc) (b/doc)
    "heading+ paragraph+" (b/doc (b/h1)) (b/doc (b/p)) (b/doc)
    "heading+ paragraph+" (b/doc (b/h1)) (b/doc) (b/doc (b/p))
    "hard_break{3}" (b/p (b/br)) (b/p (b/br)) (b/p (b/br))
    "hard_break{3}" (b/p (b/br) (b/br)) (b/p (b/br) (b/br)) nil
    "code_block{2} paragraph{2}" (b/doc (b/pre)) (b/doc (b/p)) (b/doc (b/pre) (b/p))
    "heading paragraph? horizontal_rule" (b/doc (b/h1)) (b/doc) (b/doc (b/hr))
))

(deftest fill-three-tables
  (are [expr before mid after left right]
    (let [start (content/match-fragment (get-match expr) (:content before))
          a (content/fill-before start (:content mid))
          b (when a
              (content/fill-before
               (content/match-fragment start (into a (:content mid)))
               (:content after) true))]
      (if left
        (and (= a (:content left)) (= b (:content right)))
        (nil? b)))
    "paragraph horizontal_rule paragraph horizontal_rule paragraph" (b/doc (b/p)) (b/doc (b/p)) (b/doc (b/p)) (b/doc (b/hr)) (b/doc (b/hr))
    "code_block+ paragraph+" (b/doc (b/pre)) (b/doc (b/pre)) (b/doc (b/p)) (b/doc) (b/doc)
    "code_block+ paragraph+" (b/doc) (b/doc) (b/doc) (b/doc) (b/doc (b/pre) (b/p))
    "code_block{3} paragraph{3}" (b/doc (b/pre)) (b/doc (b/p)) (b/doc) (b/doc (b/pre) (b/pre)) (b/doc (b/p) (b/p))
    "paragraph*" (b/doc (b/p)) (b/doc (b/pre)) (b/doc (b/p)) nil nil
    "paragraph{4}" (b/doc (b/p)) (b/doc (b/p)) (b/doc (b/p)) (b/doc) (b/doc (b/p))
    "paragraph{2}" (b/doc (b/p)) (b/doc (b/p)) (b/doc (b/p)) nil nil
))
