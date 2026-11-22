(ns pm.model.node-test
  (:require [clojure.test :refer [deftest is are]]
            [pm.model.node :as node]
            [pm.model.schema :as s]
            [pm.test-builder :as b]))

;; Initial phase-2 cases from test-node.ts: check and JSON round trips.
(deftest round-trips
  (are [doc] (node/eq doc (node/from-json b/schema (node/to-json doc)))
    (b/doc (b/p "foo"))
    (b/doc (b/p "foo" (b/em "bar" (b/strong "baz")) " " (b/a "x")))
    (b/doc (b/p "foo" (b/em (b/img) "bar")))
    (b/doc (b/p "a") (b/hr) (b/p "b") (b/p))
    (b/doc (b/blockquote (b/ul (b/li (b/p "a") (b/p "b")) (b/li (b/p (b/img)))) (b/p "c")) (b/p "d"))))

(deftest check-invalid
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid content for node doc"
                       (node/check (b/doc (b/li "x")))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid content for node doc"
                       (node/check (b/doc (assoc (b/p) :marks [(s/mark b/schema "em")])))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid collection of marks"
                       (node/check (assoc (s/text b/schema "a") :marks
                                          [(s/mark b/schema "em") (s/mark b/schema "em")]))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected value of type string"
                       (node/check (b/img {:src true})))))

(deftest attribute-validation
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected value of type number"
                       (b/h1 {:level "huh"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected value of type string"
                       (s/mark b/schema "link" {:href 55})))
  (is (node/eq (b/h1 {:level 2}) (b/h1 {:level 2.0})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unsupported attribute"
                       (node/check (assoc-in (b/p) [:attrs :unknown] true)))))

(deftest malformed-json
  (are [json] (thrown? clojure.lang.ExceptionInfo (node/from-json b/schema json))
    nil {} {:type "evil"} {:type "text" :text ""} {:type "text" :text 12}
    {:type "paragraph" :content {}} {:type "paragraph" :marks {}}
    {:type "text" :text "x" :marks [{:type "evil"}]})
  ;; Parsing is not recursive content validation; check is the authority gate.
  (is (thrown? clojure.lang.ExceptionInfo
               (node/check (node/from-json b/schema {:type "doc" :content [{:type "text" :text "x"}]})))))
