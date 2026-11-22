(ns pm.model.schema-test
  (:require [clojure.test :refer [deftest are is]]
            [pm.model.schema :as s]
            [pm.model.node :as n]
            [pm.test-builder :as b]))

(deftest invalid-schemas
  (are [spec] (thrown? clojure.lang.ExceptionInfo (s/schema spec))
    {:nodes [["text" {}]]}
    {:nodes [["doc" {}]]}
    {:nodes [["doc" {}] ["text" {:attrs {:x {:default 1}}}]]}
    {:nodes [["doc" {}] ["text" {}]] :marks [["doc" {}]]}
    {:nodes [["doc" {:content "text*" :marks "missing"}] ["text" {}]]}
    {:nodes [["doc" {}] ["text" {}]] :marks [["em" {:excludes "missing"}]]}))

(deftest constructor-contract
  (is (thrown? clojure.lang.ExceptionInfo (s/create (get-in b/schema [:nodes "text"]))))
  (is (thrown? clojure.lang.ExceptionInfo (s/text b/schema 3)))
  (is (thrown? clojure.lang.ExceptionInfo
               (s/create-checked (get-in b/schema [:nodes "doc"]) nil [(s/text b/schema "x")]))))

(deftest safe-printing
  (is (< (count (pr-str (b/doc (b/p "x")))) 10000)))

(deftest check-valid-and-attrs
  (is (nil? (n/check (b/doc (b/p "ok" (b/em "bold") (b/img))))))
  (is (thrown? clojure.lang.ExceptionInfo (b/img {:alt 12})))
  (is (thrown? clojure.lang.ExceptionInfo (b/a {:title 12} "x")))
  (is (thrown? clojure.lang.ExceptionInfo (b/ol {:order "1"} (b/li (b/p))))))
