(ns pm.model.replace-test
  "Translated from prosemirror-model test/test-slice.ts and test-replace.ts."
  (:require [clojure.test :refer [deftest is are]]
            [pm.model.fragment :as f]
            [pm.model.node :as n]
            [pm.model.replace :as r]
            [pm.test-builder :refer [doc p li ul em a blockquote h1]]))

(defn- tag [node k] (get-in (meta node) [:tag k]))
(defn- schema-of [node] @(get-in node [:type :registry]))

;; ---- test-slice.ts ----
(defn- slice-is? [d expect open-start open-end]
  (let [s (r/slice d (or (tag d "a") 0) (tag d "b"))]
    (and (f/eq (:content s) (:content expect))
         (= open-start (:open-start s))
         (= open-end (:open-end s)))))

(deftest slicing
  (are [d expect os oe] (slice-is? d expect os oe)
    (doc (p "hello<b> world")) (doc (p "hello")) 0 1
    (doc (p "hello<b>")) (doc (p "hello")) 0 1
    (doc (p "hello<b> world") (p "rest")) (doc (p "hello")) 0 1
    (doc (p "hello " (em "WOR<b>LD"))) (doc (p "hello " (em "WOR"))) 0 1
    (doc (p "a") (p "b<b>")) (doc (p "a") (p "b")) 0 1
    (doc (p "a") "<b>" (p "b")) (doc (p "a")) 0 0
    (doc (blockquote (ul (li (p "a")) (li (p "b<b>")))))
    (doc (blockquote (ul (li (p "a")) (li (p "b"))))) 0 4
    (doc (p "hello<a> world")) (doc (p " world")) 1 0
    (doc (p "<a>hello")) (doc (p "hello")) 1 0
    (doc (p "foo") (p "bar<a>baz")) (doc (p "baz")) 1 0
    (doc (p "a sentence with an " (em "emphasized " (a "li<a>nk")) " in it"))
    (doc (p (em (a "nk")) " in it")) 1 0
    (doc (p "a " (em "sentence") " wi<a>th " (em "text") " in it"))
    (doc (p "th " (em "text") " in it")) 1 0
    (doc (p "a") "<a>" (p "b")) (doc (p "b")) 0 0
    (doc (blockquote (ul (li (p "a")) (li (p "<a>b")))))
    (doc (blockquote (ul (li (p "b"))))) 4 0
    (doc (p "hell<a>o wo<b>rld")) (p "o wo") 0 0
    (doc (p "on<a>e") (p "t<b>wo")) (doc (p "e") (p "t")) 1 1
    (doc (p "here's noth<a>ing and " (em "here's e<b>m")))
    (p "ing and " (em "here's e")) 0 0
    (doc (ul (li (p "hello")) (li (p "wo<a>rld")) (li (p "x"))) (p (em "bo<b>o")))
    (doc (ul (li (p "rld")) (li (p "x"))) (p (em "bo"))) 3 1
    (doc (blockquote (p "foo<a>bar") (ul (li (p "a")) (li (p "b") "<b>" (p "c"))) (p "d")))
    (blockquote (p "bar") (ul (li (p "a")) (li (p "b")))) 1 2))

(deftest slice-include-parents
  (let [d (doc (blockquote (p "fo<a>o") (p "bar<b>")))
        s (r/slice d (tag d "a") (tag d "b") true)]
    (is (= "<blockquote(paragraph(\"o\"), paragraph(\"bar\"))>(2,2)" (r/slice-to-string s)))))

(deftest slice-basics
  (let [d (doc (p "a")) s (r/slice d 1 2)]
    (is (= r/empty-slice (r/slice d 1 1)))
    (is (= 1 (r/slice-size s)))
    (is (r/slice-eq s (r/slice-from-json (schema-of d) (r/slice-to-json s))) "JSON round trip")
    (is (nil? (r/slice-to-json r/empty-slice)))
    (is (= r/empty-slice (r/slice-from-json (schema-of d) nil)))))

;; ---- test-replace.ts ----
(defn- do-replace [d insert]
  (let [s (if insert (r/slice insert (tag insert "a") (tag insert "b")) r/empty-slice)]
    (r/replace d (tag d "a") (tag d "b") s)))

(deftest replace-ok
  (are [d insert expected] (n/eq expected (do-replace d insert))
    (doc (p "on<a>e") (p "t<b>wo")) nil (doc (p "onwo"))
    (doc (p "on<a>e") (p "t<b>wo")) (doc (p "xx<a>xx") (p "yy<b>yy")) (doc (p "onxx") (p "yywo"))
    (doc (p "on<a>e") (p "t<b>wo")) (doc (p "<a>H<b>")) (doc (p "onHwo"))
    (doc (p "before") (p "on<a><b>e") (p "after")) (doc (p "<a>H<b>"))
    (doc (p "before") (p "onHe") (p "after"))
    (doc (p "on<a>e") (p "t<b>wo")) (doc (h1 "<a>H<b>")) (doc (p "onHwo"))
    (doc (blockquote (blockquote (p "on<a>e") (p "t<b>wo")))) (doc (p "<a>H<b>"))
    (doc (blockquote (blockquote (p "onHwo"))))
    (doc (blockquote (p "a<a>bc<b>d"))) (doc (p "x<a>y<b>z")) (doc (blockquote (p "ayd")))
    (doc (blockquote (blockquote (p "on<a>e") (p "two") "<b>" (p "three"))))
    (doc (blockquote (p "aa<a>aa") (p "bb") (p "cc") "<b>" (p "dd")))
    (doc (blockquote (blockquote (p "onaa") (p "bb") (p "cc") (p "three"))))
    (doc (blockquote (blockquote (p "on<a>e") (p "two") (p "three")) "<b>" (p "x")))
    (doc (blockquote (p "aa<a>aa") (p "bb") (p "cc")) "<b>" (p "dd"))
    (doc (blockquote (blockquote (p "onaa") (p "bb") (p "cc")) (p "x")))
    (doc (blockquote (blockquote (p "hell<a>o"))) (blockquote (blockquote (p "<b>a")))) nil
    (doc (blockquote (blockquote (p "hella"))))
    (doc (blockquote (blockquote (p "hell<a>o"))) (blockquote (blockquote (p "<b>a"))))
    (doc (p "<a>i<b>")) (doc (blockquote (blockquote (p "hellia"))))
    (doc (p "foo<a><b>bar")) (doc (p "<a>x") (p "y<b>")) (doc (p "foox") (p "ybar"))
    (doc (blockquote (p "foo<a>x<b>bar"))) (doc (blockquote (p "<a>x")) (blockquote (p "y<b>")))
    (doc (blockquote (p "foox")) (blockquote (p "ybar")))
    (doc (blockquote (p "foo<a>u") (p "v<b>bar"))) (doc (blockquote (p "<a>x")) (blockquote (p "y<b>")))
    (doc (blockquote (p "foox")) (blockquote (p "ybar")))
    (doc (h1 "foo<a>bar") "<b>") (doc (p "foo<a>baz") "<b>") (doc (h1 "foobaz"))
    (doc (h1 "<a>bar") "<b>") (doc (p "foo<a>baz") "<b>") (doc (h1 "baz"))))

(deftest replace-rejected
  (are [d insert pattern]
    (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern (str "(?i)" pattern)) (do-replace d insert))
    (doc (p "<a><b>")) (doc (blockquote (p "<a>")) "<b>") "deeper"
    (doc (p "<a><b>")) (doc "<a>" (p "<b>")) "inconsistent"
    (doc "<a><b>") (doc (p "<a>foo<b>")) "invalid content"
    (doc (ul (li (p "a")) "<a>") "<b>") (doc (p "foo" "<a>") "<b>") "cannot join"
    (doc (blockquote (p "a") "<a>") (ul "<b>" (li (p "b")))) nil "cannot join"
    (doc (blockquote "<a>" (p "hi")) "<b>") (doc (blockquote "hi" "<a>") "<b>") "invalid content"))

(deftest replace-error-is-typed
  (let [e (try (do-replace (doc (p "<a><b>")) (doc (blockquote (p "<a>")) "<b>")) (catch Exception e e))]
    (is (= ::r/replace-error (:type (ex-data e))))))

(deftest slice-editing
  (let [s (r/slice-from-json pm.test-builder/schema
                             {:content [{:type "paragraph" :content [{:type "text" :text "abc"}]}] :openStart 1 :openEnd 1})]
    (is (= 3 (r/slice-size s)))
    (is (r/slice-eq (r/remove-between s 1 2)
                    (r/slice-from-json pm.test-builder/schema
                                       {:content [{:type "paragraph" :content [{:type "text" :text "ac"}]}] :openStart 1 :openEnd 1})))
    (is (r/slice-eq (r/insert-at s 1 [(pm.model.schema/text pm.test-builder/schema "X")])
                    (r/slice-from-json pm.test-builder/schema
                                       {:content [{:type "paragraph" :content [{:type "text" :text "aXbc"}]}] :openStart 1 :openEnd 1})))
    ;; With openStart 0 the paragraph is closed, so inserting a block into it must be checked.
    (is (nil? (r/insert-at (r/slice-from-json pm.test-builder/schema
                                              {:content [{:type "paragraph" :content [{:type "text" :text "abc"}]}]})
                           1 [(pm.test-builder/hr)])))
    (is (thrown? clojure.lang.ExceptionInfo (r/remove-between (r/slice (doc (p "a") (p "b")) 0 6) 1 4)))
    (let [m (r/max-open [(p "x")])] (is (= [1 1] [(:open-start m) (:open-end m)])))))
