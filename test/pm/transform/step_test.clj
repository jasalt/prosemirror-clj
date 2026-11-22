(ns pm.transform.step-test
  "Translated from prosemirror-transform test-step.ts and test-replace_step.ts,
  plus JSON, invert and malformed-input regressions (not upstream cases)."
  (:require [clojure.test :refer [deftest is are]]
            [pm.model.fragment :as f]
            [pm.model.node :as n]
            [pm.model.replace :as r]
            [pm.model.schema :as s]
            [pm.test-builder :as b :refer [doc p blockquote em h1 img li ul]]
            [pm.transform :as t]
            [pm.transform.map :as m]))

(def test-doc (doc (p "foobar")))
(def schema b/schema)

(defn mk-step [from to val]
  (case val
    "+em" (t/add-mark-step from to (s/mark schema "em"))
    "-em" (t/remove-mark-step from to (s/mark schema "em"))
    (t/replace-step from to (if val (r/make-slice [(s/text schema val)] 0 0) r/empty-slice))))

(defn apply-doc [step d] (:doc (t/apply-step step d)))

;; ---- test-step.ts: merge ----
(deftest merging
  (are [f1 t1 v1 f2 t2 v2]
    (let [s1 (mk-step f1 t1 v1) s2 (mk-step f2 t2 v2) merged (t/merge-steps s1 s2)]
      (and merged (n/eq (apply-doc merged test-doc) (apply-doc s2 (apply-doc s1 test-doc)))))
    2 2 "a" 3 3 "b"          ; typing
    2 2 "a" 2 2 "b"          ; inverse typing
    3 4 nil 2 3 nil          ; adjacent backspaces
    2 3 nil 2 3 nil          ; adjacent deletes
    2 3 nil 2 2 "x"          ; backspace and type
    2 2 "quux" 6 6 "baz"
    2 2 "quux" 2 2 "baz"
    2 5 nil 2 4 nil
    4 6 nil 2 4 nil
    3 4 "x" 4 5 "y"
    1 2 "+em" 2 4 "+em"
    1 3 "+em" 2 4 "+em"
    1 2 "-em" 2 4 "-em"
    1 3 "-em" 2 4 "-em"))

(deftest not-merging
  (are [f1 t1 v1 f2 t2 v2] (nil? (t/merge-steps (mk-step f1 t1 v1) (mk-step f2 t2 v2)))
    2 2 "a" 4 4 "b"
    3 3 "a" 2 2 "b"
    1 2 nil 2 3 nil
    1 2 "+em" 3 4 "+em"
    1 2 "-em" 3 4 "-em"))

;; ---- test-replace_step.ts ----
(deftest replace-around-must-fit
  (let [step (t/replace-around-step 1 2 1 2 (r/make-slice [(blockquote)] 0 0) 1 true)
        res (t/apply-step step (doc (p "a")))]
    (is (nil? (:doc res)))
    (is (re-find #"Content does not fit in gap" (:failed res)))))

(deftest replace-around-considers-openness
  (let [d (doc (blockquote (p "x")))
        res (t/apply-step (t/replace-around-step 0 1 1 1 (r/make-slice [(blockquote)] 0 1) 1 true) d)]
    (is (nil? (:failed res)))
    (is (n/eq d (:doc res)))))

(deftest collapsed-replace-around-maps-to-nil
  (doseq [pos [0 5]]
    (let [step (t/replace-around-step pos pos pos pos r/empty-slice 0 true)
          before (doc (p "abcd")) applied (t/apply-step step before)]
      (is (nil? (:failed applied)))
      (is (n/eq before (:doc applied)))
      (is (n/eq before (apply-doc (t/invert-step step before) (:doc applied))))
      (is (nil? (t/map-step step (m/mapping [(m/step-map [pos 0 10])])))))))

;; ---- structure-flag guards ----
(deftest structure-replace-guards
  (let [d (doc (p "ab"))]
    (is (re-find #"Structure replace would overwrite content"
                 (:failed (t/apply-step (t/replace-step 0 4 r/empty-slice true) d))))
    (is (nil? (:failed (t/apply-step (t/replace-step 0 0 (r/make-slice [(p "x")] 0 0) true) d))))))

;; ---- results & maps ----
(deftest replace-step-maps-and-inverts
  (let [d (doc (p "hello")) step (t/replace-step 2 4 (r/make-slice [(s/text schema "XYZ")] 0 0))
        out (apply-doc step d)]
    (is (n/eq (doc (p "hXYZlo")) out))
    (is (= [2 2 3] (:ranges (t/get-map step))))
    (is (n/eq d (apply-doc (t/invert-step step d) out)))))

(deftest mark-steps
  (let [d (doc (p "hello")) add (t/add-mark-step 2 4 (s/mark schema "em"))
        out (apply-doc add d)]
    (is (n/eq (doc (p "h" (em "el") "lo")) out))
    (is (n/eq d (apply-doc (t/invert-step add d) out)))
    (is (n/eq d (apply-doc (t/remove-mark-step 2 4 (s/mark schema "em")) out)))
    (is (nil? (t/map-step add (m/mapping [(m/step-map [0 6 0])]))))))

(deftest add-mark-skips-atoms-that-disallow-the-mark
  (let [d (doc (p "a" (img) "b"))
        out (apply-doc (t/add-mark-step 1 4 (s/mark schema "em")) d)]
    (is (n/eq (doc (p (em "a") (em (img)) (em "b"))) out))))

(deftest node-mark-and-attr-steps
  (let [d (doc (p "a" (img) "b")) em* (s/mark schema "em")
        added (apply-doc (t/add-node-mark-step 2 em*) d)]
    (is (n/eq (doc (p "a" (em (img)) "b")) added))
    (is (n/eq d (apply-doc (t/invert-step (t/add-node-mark-step 2 em*) d) added)))
    (is (n/eq d (apply-doc (t/remove-node-mark-step 2 em*) added)))
    (is (= "No node at mark step's position" (:failed (t/apply-step (t/add-node-mark-step 99 em*) d)))))
  (let [d (doc (h1 "a")) step (t/attr-step 0 "level" 2) out (apply-doc step d)]
    (is (= 2 (get-in out [:content 0 :attrs :level])))
    (is (n/eq d (apply-doc (t/invert-step step d) out)))
    (is (= "No node at attribute step's position" (:failed (t/apply-step (t/attr-step 50 "level" 2) d)))))
  (let [sch (s/schema {:nodes [["doc" {:content "text*" :attrs {:meta {:default nil}}}] ["text" {}]]})
        d (s/create (get-in sch [:nodes "doc"]) nil [])
        step (t/doc-attr-step "meta" "hello") out (apply-doc step d)]
    (is (= "hello" (get-in out [:attrs :meta])))
    (is (n/eq d (apply-doc (t/invert-step step d) out)))))

;; ---- JSON ----
(deftest json-round-trip
  (let [steps [(t/replace-step 1 2 (r/make-slice [(p "x")] 1 1))
               (t/replace-step 1 1 r/empty-slice true)
               (t/replace-around-step 0 5 1 4 (r/make-slice [(blockquote)] 0 1) 1 true)
               (t/add-mark-step 1 3 (s/mark schema "link" {:href "u" :title nil}))
               (t/remove-mark-step 1 3 (s/mark schema "em"))
               (t/add-node-mark-step 2 (s/mark schema "em"))
               (t/remove-node-mark-step 2 (s/mark schema "em"))
               (t/attr-step 0 "level" 3)
               (t/doc-attr-step "x" nil)]]
    (doseq [step steps]
      (let [json (t/step-to-json step)]
        (is (string? (:stepType json)))
        (is (= json (t/step-to-json (t/step-from-json schema json))))))))

(deftest json-key-shapes
  (is (= {:stepType "replace" :from 1 :to 2} (t/step-to-json (t/replace-step 1 2 r/empty-slice))))
  (is (= {:stepType "replace" :from 1 :to 2 :slice {:content [{:type "paragraph"}] :openStart 1 :openEnd 1} :structure true}
         (t/step-to-json (t/replace-step 1 2 (r/make-slice [(p)] 1 1) true)))))

(deftest malformed-step-json
  (are [json pattern] (thrown-with-msg? clojure.lang.ExceptionInfo pattern (t/step-from-json schema json))
    nil #"Invalid input for Step.fromJSON"
    {} #"Invalid input for Step.fromJSON"
    {:stepType "evil"} #"No step type evil defined"
    {:stepType "replace" :from "1" :to 2} #"Invalid input for ReplaceStep.fromJSON"
    {:stepType "replace" :from 1} #"Invalid input for ReplaceStep.fromJSON"
    {:stepType "replace" :from 1 :to 1.5} #"Invalid input for ReplaceStep.fromJSON"
    {:stepType "replaceAround" :from 1 :to 2} #"Invalid input for ReplaceAroundStep.fromJSON"
    {:stepType "addMark" :from 1 :to 2 :mark nil} #"Invalid input for Mark.fromJSON"
    {:stepType "addMark" :from 1 :to 2 :mark {:type "nope"}} #"There is no mark type nope in this schema"
    {:stepType "attr" :pos 1 :attr 7} #"Invalid input for AttrStep.fromJSON"
    {:stepType "docAttr"} #"Invalid input for DocAttrStep.fromJSON"
    {:stepType "markSet" :steps [{:stepType "replace" :from 1 :to 1}]} #"Invalid JSON for MarkSetStep"
    {:stepType "replace" :from 1 :to 2 :slice {:content [{:type "script"}]}} #"Unknown node type: script"))

(deftest apply-rejections
  (let [d (doc (p "a"))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Position 99999 out of range"
                          (t/apply-step (t/replace-step 99999 99999 r/empty-slice) d)))
    (is (re-find #"Invalid content for node doc"
                 (:failed (t/apply-step (t/replace-step 0 3 (r/make-slice [(s/text schema "x")] 0 0)) d))))))

(deftest apply-steps-sequence
  ;; apply-steps is the server-side batch primitive: all-or-nothing, with the failing index.
  (let [d (doc (p "ab"))
        ok (t/replace-step 1 1 (r/make-slice [(s/text schema "X")] 0 0))
        bad (t/replace-step 0 3 (r/make-slice [(s/text schema "y")] 0 0))]
    (is (n/eq (doc (p "Xab")) (:doc (t/apply-steps [ok] d))))
    (let [res (t/apply-steps [ok bad] d)]
      (is (nil? (:doc res)))
      (is (= 1 (:index res)))
      (is (re-find #"Invalid content" (:failed res))))
    (is (n/eq d (:doc (t/apply-steps [] d))))))
