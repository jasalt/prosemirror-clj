(ns pm.model.resolve-test
  "Translated from prosemirror-model test/test-resolve.ts and the
  ResolvedPos.marks block of test-mark.ts."
  (:require [clojure.test :refer [deftest is are]]
            [pm.model.fragment :as f]
            [pm.model.mark :as m]
            [pm.model.node :as n]
            [pm.model.resolvedpos :as rp]
            [pm.model.schema :as s]
            [pm.test-builder :as b :refer [doc p em a blockquote]]))

(def test-doc (doc (p "ab") (blockquote (p (em "cd") "ef"))))
(defn child [node i] (get (:content node) i))
(def d {:node test-doc :start 0 :end 12})
(def p1 {:node (child test-doc 0) :start 1 :end 3})
(def blk {:node (child test-doc 1) :start 5 :end 11})
(def p2 {:node (child (:node blk) 0) :start 6 :end 10})

;; pos -> [path-entries parent-offset node-before node-after]; strings compare text content.
(def expected
  {0 [[d] 0 nil (:node p1)]
   1 [[d p1] 0 nil "ab"]
   2 [[d p1] 1 "a" "b"]
   3 [[d p1] 2 "ab" nil]
   4 [[d] 4 (:node p1) (:node blk)]
   5 [[d blk] 0 nil (:node p2)]
   6 [[d blk p2] 0 nil "cd"]
   7 [[d blk p2] 1 "c" "d"]
   8 [[d blk p2] 2 "cd" "ef"]
   9 [[d blk p2] 3 "e" "f"]
   10 [[d blk p2] 4 "ef" nil]
   11 [[d blk] 6 (:node p2) nil]
   12 [[d] 12 (:node blk) nil]})

(defn- same? [actual exp]
  (cond (nil? exp) (nil? actual)
        (string? exp) (= exp (n/text-content actual))
        :else (n/eq actual exp)))

(deftest reflects-document-structure
  (doseq [pos (range 0 (inc (f/size (:content test-doc))))]
    (let [$pos (rp/resolve test-doc pos)
          [path offset before after] (expected pos)]
      (is (= (dec (count path)) (:depth $pos)) (str "depth at " pos))
      (doseq [[i {:keys [node start end]}] (map-indexed vector path)]
        (is (n/eq node (rp/node $pos i)))
        (is (= start (rp/start $pos i)))
        (is (= end (rp/end $pos i)))
        (when (pos? i)
          (is (= (dec start) (rp/before $pos i)))
          (is (= (inc end) (rp/after $pos i)))))
      (is (= offset (:parent-offset $pos)))
      (is (same? (rp/node-before $pos) before) (str "before at " pos))
      (is (same? (rp/node-after $pos) after) (str "after at " pos)))))

(deftest pos-at-index
  (let [d (doc (blockquote (p "one") (blockquote (p "two " (em "three")) (p "four"))))
        $ (rp/resolve d 12)]
    (are [index depth expected] (= expected (rp/pos-at-index $ index depth))
      0 nil 8, 1 nil 12, 2 nil 17
      0 2 7, 1 2 18, 2 2 24
      0 1 1, 1 1 6, 2 1 25
      0 0 0, 1 0 26)))

(deftest resolve-bounds
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Position 13 out of range" (rp/resolve test-doc 13)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Position -1 out of range" (rp/resolve test-doc -1)))
  (is (thrown? clojure.lang.ExceptionInfo (rp/before (rp/resolve test-doc 0) 0)))
  (is (thrown? clojure.lang.ExceptionInfo (rp/after (rp/resolve test-doc 0) 0))))

(deftest derived-accessors
  (let [$ (rp/resolve test-doc 7)]   ; inside the text node "cd"
    (is (= 1 (:text-offset $)))
    (is (= "blockquote_1/paragraph_0:1" (rp/to-string $)))
    (is (= 0 (rp/index $ 2)))
    (is (= 1 (rp/index-after $ 2)))   ; mid-text: after the current node
    (is (= 1 (rp/index-after $ 1))))
  (let [$ (rp/resolve test-doc 8)]    ; boundary between "cd" and "ef"
    (is (= 0 (:text-offset $)))
    (is (= 1 (rp/index $ 2)))
    (is (= 1 (rp/index-after $ 2)))   ; at a boundary: same index
    (is (= 1 (rp/index-after $ 1))))
  (let [$ (rp/resolve test-doc 8)]
    (is (= 1 (rp/shared-depth (rp/resolve test-doc 2) 3)))
    (is (= 0 (rp/shared-depth (rp/resolve test-doc 2) 4)))
    (is (rp/same-parent (rp/resolve test-doc 6) (rp/resolve test-doc 10)))
    (is (= 9 (:pos (rp/max (rp/resolve test-doc 3) (rp/resolve test-doc 9)))))
    (is (= 3 (:pos (rp/min (rp/resolve test-doc 3) (rp/resolve test-doc 9)))))))

(deftest block-range
  (let [r (rp/block-range (rp/resolve test-doc 7) (rp/resolve test-doc 9))]
    (is (= 1 (:depth r)))   ; textblock parent, so start one level up
    (is (= 5 (rp/range-start r)))
    (is (= 11 (rp/range-end r)))
    (is (= 0 (rp/range-start-index r)))
    (is (= 1 (rp/range-end-index r)))
    (is (= "blockquote" (get-in (rp/range-parent r) [:type :name]))))
  (is (= 0 (:depth (rp/block-range (rp/resolve test-doc 2) (rp/resolve test-doc 8)))))
  (is (nil? (rp/block-range (rp/resolve test-doc 2) (rp/resolve test-doc 8) #(= "nope" (get-in % [:type :name]))))))

;; ---- ResolvedPos.marks (test-mark.ts) ----
(def em* (s/mark b/schema "em"))
(def strong* (s/mark b/schema "strong"))
(defn link* [href] (s/mark b/schema "link" {:href href :title nil}))

(defn- marks-at [d] (rp/marks (rp/resolve d (get-in (meta d) [:tag "a"]))))

(deftest marks-at-position
  (are [d mark expected] (= expected (m/is-in-set mark (marks-at d)))
    (doc (p (em "fo<a>o"))) em* true
    (doc (p (em "fo<a>o"))) strong* false
    (doc (p (em "hi") "<a> there")) em* true
    (doc (p "one <a>" (em "two"))) em* false
    (doc (p (em "<a>one"))) em* true
    (doc (p (a "li<a>nk"))) (link* "http://baz") false))

(def cschema (s/schema {:nodes [["doc" {:content "paragraph+"}]
                                ["paragraph" {:content "text*"}] ["text" {}]]
                        :marks [["remark" {:attrs {:id {}} :excludes "" :inclusive false}]
                                ["user" {:attrs {:id {}} :excludes "_"}]
                                ["strong" {:excludes "em-group"}]
                                ["em" {:group "em-group"}]]}))
(def remark1 (s/mark cschema "remark" {:id 1}))
(def remark2 (s/mark cschema "remark" {:id 2}))
(def cstrong (s/mark cschema "strong"))
(defn ct [t & marks] (s/text cschema t (vec marks)))
(defn cn [name children] (s/create (get-in cschema [:nodes name]) nil children))
(def custom-doc
  (cn "doc" [(cn "paragraph" [(ct "one" remark1 cstrong) (ct "two")])
             (cn "paragraph" [(ct "one") (ct "two" remark1) (ct "three" remark1)])
             (cn "paragraph" [(ct "one" remark2) (ct "two" remark1)])]))

(deftest non-inclusive-marks
  (are [pos expected] (m/same-set expected (rp/marks (rp/resolve custom-doc pos)))
    4 [cstrong]           ; omits non-inclusive marks at end of mark
    3 [remark1 cstrong]   ; includes them inside a text node
    20 []                 ; omits at the end of a line
    15 [remark1]          ; includes between two marked nodes
    25 []))               ; excludes where mark attrs change

(deftest marks-across
  (let [d (doc (p (em "ab") "cd"))]
    (is (nil? (rp/marks-across (rp/resolve d 0) (rp/resolve d 1))))
    (is (m/same-set [em*] (rp/marks-across (rp/resolve d 2) (rp/resolve d 4))))))
