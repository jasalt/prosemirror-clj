(ns pm.model.tree-test
  (:require [clojure.test :refer [deftest are is]]
            [pm.model.node :as n]
            [pm.model.fragment :as f]
            [pm.model.schema :as s]
            [pm.test-builder :as b]
            [pm.test-builder :refer [doc p ul li blockquote em strong code img br hr]]))

;; Remaining tree operations from upstream test-node.ts.
(deftest debug-strings
  (are [node expected] (= expected (n/to-string node))
    (doc (ul (li (p "hey") (p)) (li (p "foo"))))
    "doc(bullet_list(list_item(paragraph(\"hey\"), paragraph), list_item(paragraph(\"foo\"))))"
    (doc (p "foo" (img) (br) "bar")) "doc(paragraph(\"foo\", image, hard_break, \"bar\"))"
    (doc (p "foo" (em "bar" (strong "quux")) (code "baz")))
    "doc(paragraph(\"foo\", em(\"bar\"), em(strong(\"quux\")), code(\"baz\")))"
    (br) "hard_break"
    (s/text b/schema "hello") "\"hello\""))

(deftest cuts
  (are [node expected]
    (n/eq expected (n/cut node (get-in (meta node) [:tag "a"] 0)
                        (get-in (meta node) [:tag "b"] (f/size (:content node)))))
    (doc (p "foo") "<a>" (p "bar") "<b>" (p "baz")) (doc (p "bar"))
    (doc (p "0") (p "foo<a>bar<b>baz") (p "2")) (doc (p "bar"))
    (doc (blockquote (ul (li (p "a") (p "b<a>c")) (li (p "d")) "<b>" (li (p "e"))) (p "3")))
    (doc (blockquote (ul (li (p "c")) (li (p "d")))))
    (doc (blockquote (p "foo<b>bar"))) (doc (blockquote (p "foo")))
    (doc (blockquote (p "foo<a>bar"))) (doc (blockquote (p "bar")))
    (doc (p "foo" (em "ba<a>r" (img) (strong "baz") (br)) "qu<b>ux" (code "xyz")))
    (doc (p (em "r" (img) (strong "baz") (br)) "qu"))))

(deftest between
  (are [node expected]
    (let [d node seen (atom [])]
      (n/nodes-between d (get-in (meta d) [:tag "a"]) (get-in (meta d) [:tag "b"])
                       (fn [child pos _ _]
                         (swap! seen conj (or (:text child) (get-in child [:type :name])))
                         (is (identical? child (n/node-at d pos)))
                         nil))
      (= expected @seen))
    (doc (p "foo<a>bar<b>baz")) ["paragraph" "foobarbaz"]
    (doc (blockquote (ul (li (p "f<a>oo")) (p "b") "<b>") (p "c")))
    ["blockquote" "bullet_list" "list_item" "paragraph" "foo" "paragraph" "b"]
    (doc (p (em "x") "f<a>oo" (em "bar" (img) (strong "baz") (br)) "quux" (code "xy<b>z")))
    ["paragraph" "foo" "bar" "image" "baz" "hard_break" "quux" "xyz"]))

(deftest text-extraction
  (are [node separator leaf expected]
    (= expected (n/text-between node 0 (f/size (:content node)) separator leaf))
    (doc (p "one") (p) (p "two")) "\n" nil "one\n\ntwo"
    (doc (p "one") (hr) (hr) (p "two")) "\n" "---" "one\n---\n---\ntwo"
    (doc (p "one") (hr) (hr) (p "two")) "\n" nil "one\ntwo"
    (doc (p "foo" (img) (br))) "" #(case (get-in % [:type :name]) "image" "<image>" "hard_break" "<break>" "")
    "foo<image><break>")
  (are [node expected] (= expected (n/text-content node))
    (doc (p "foo")) "foo"
    (first (:content (p "foo"))) "foo"
    (doc (ul (li (p "hi")) (li (p (em "a") "b")))) "hiab"))

(deftest fragment-from
  (are [value expected] (f/eq (:content expected) (f/from value))
    (p) (doc (p))
    [(br) (first (:content (p "foo")))] (p br "foo")
    (:content (doc (p "foo"))) (doc (p "foo"))
    nil (p)
    (into (:content (p "a")) (:content (p "b"))) (p "ab")))

(def custom
  (s/schema {:nodes [["doc" {:content "paragraph+"}]
                     ["paragraph" {:content "(text|contact)*"}]
                     ["text" {:toDebugString (constantly "custom_text")}]
                     ["contact" {:inline true :attrs {:name {} :email {}}
                                 :leafText #(str (get-in % [:attrs :name]) " <" (get-in % [:attrs :email]) ">")}]
                     ["hard_break" {:toDebugString (constantly "custom_hard_break")}]]}))
(deftest custom-rendering
  (let [contact (s/create (get-in custom [:nodes "contact"]) {:name "Bob" :email "bob@example.com"} [])
        para (s/create (get-in custom [:nodes "paragraph"]) {} [(s/text custom "Hello ") contact])]
    (is (= "Bob <bob@example.com>" (n/text-content contact)))
    (is (= "Hello Bob <bob@example.com>" (n/text-content para)))
    (let [d (s/create-checked (get-in custom [:nodes "doc"]) {} [para])]
      (is (= "Hello Bob <bob@example.com>" (n/text-between d 0 (f/size (:content d)))))
      (is (= "Hello <anonymous>" (n/text-between d 0 (f/size (:content d)) "" "<anonymous>"))))
    (is (= "Hello <anonymous>" (n/text-between para 0 (f/size (:content para)) "" "<anonymous>")))
    (is (= "custom_text" (n/to-string (s/text custom "hello"))))
    (is (= "<custom_text, custom_hard_break, custom_text>"
           (f/to-string [(s/text custom "hello") (s/create (get-in custom [:nodes "hard_break"]))
                         (s/text custom "world")])))))
