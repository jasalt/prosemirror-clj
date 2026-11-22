(ns pm.model.mark-test
  (:require [clojure.test :refer [deftest are is]]
            [pm.model.mark :as m]
            [pm.model.schema :as s]
            [pm.test-builder :as b]))

(def em (s/mark b/schema "em"))
(def strong (s/mark b/schema "strong"))
(def code (s/mark b/schema "code"))
(defn link [href & [title]] (s/mark b/schema "link" {:href href :title title}))
(def custom (s/schema {:nodes [["doc" {:content "text*"}] ["text" {}]]
                       :marks [["remark" {:attrs {:id {}} :excludes "" :inclusive false}]
                               ["user" {:attrs {:id {}} :excludes "_"}]
                               ["strong" {:excludes "em-group"}]
                               ["em" {:group "em-group"}]]}))
(def r1 (s/mark custom "remark" {:id 1}))
(def r2 (s/mark custom "remark" {:id 2}))
(def u1 (s/mark custom "user" {:id 1}))
(def u2 (s/mark custom "user" {:id 2}))
(def ce (s/mark custom "em"))
(def cs (s/mark custom "strong"))

;; test-mark.ts, except ResolvedPos.marks (phase 3).
(deftest same-set-and-eq
  (are [a b expected] (= expected (m/same-set a b))
    [] [] true
    [em strong] [em strong] true
    [em strong] [em code] false
    [em strong] [em strong code] false
    [(link "foo") code] [(link "foo") code] true
    [(link "foo") code] [(link "bar") code] false)
  (are [a b expected] (= expected (m/eq a b))
    (link "foo") (link "foo") true
    (link "foo") (link "bar") false
    (link "foo" "A") (link "foo" "B") false
    r1 (s/mark custom "remark" {:id 1.0}) true))

(deftest add-to-set
  (are [mark marks expected] (m/same-set expected (m/add-to-set mark marks))
    em [] [em]
    em [em] [em]
    em [strong] [em strong]
    strong [em] [em strong]
    (link "bar") [(link "foo") em] [(link "bar") em]
    (link "foo") [em (link "foo")] [em (link "foo")]
    code [em strong (link "foo")] [em strong (link "foo") code]
    strong [em code] [em strong code]
    r2 [r1] [r1 r2]
    r1 [r1] [r1]
    u1 [r1 ce] [u1]
    ce [u1] [u1]
    u2 [u1] [u2]
    ce [r1 cs] [r1 cs]
    cs [r1 ce] [r1 cs]))

(deftest json-roundtrip
  (is (m/eq (link "foo") (m/from-json b/schema (m/to-json (link "foo"))))))

(deftest remove-from-set
  (are [mark marks expected] (m/same-set expected (m/remove-from-set mark marks))
    em [] []
    em [em] []
    strong [em] [em]
    (link "foo") [(link "foo")] []
    (link "foo" "title") [(link "foo")] [(link "foo")]))
