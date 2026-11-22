(ns pm.model.api-test
  (:require [clojure.test :refer [deftest is are]]
            [pm.model.schema :as s]
            [pm.model.node :as n]
            [pm.model.fragment :as f]
            [pm.test-builder :as b]))

(deftest fill-supplied-content
  (let [type (get-in b/schema [:nodes "list_item"])]
    (is (n/eq (b/li (b/p) (b/hr)) (s/create-and-fill type nil [(b/hr)])))
    (is (n/eq (b/li (b/p "x")) (s/create-and-fill type nil [(b/p "x")])))
    (is (nil? (s/create-and-fill type nil [(s/text b/schema "x")]))))
  (let [schema (s/schema {:nodes [["doc" {:content "paragraph horizontal_rule paragraph"}]
                                 ["paragraph" {}] ["horizontal_rule" {}] ["text" {}]]})
        types (:nodes schema)]
    (is (= ["paragraph" "horizontal_rule" "paragraph"]
           (mapv #(get-in % [:type :name])
                 (:content (s/create-and-fill (get types "doc") nil [(s/create (get types "horizontal_rule"))])))))))

(deftest replacement-predicates
  (let [d (b/doc (b/p "a") (b/p "b")) p (b/p "a")]
    (is (n/can-replace d 0 1))
    (is (not (n/can-replace d 0 2)))
    (is (n/can-replace d 0 2 [(b/hr)]))
    (is (not (n/can-replace d 0 1 [(s/text b/schema "x")])))
    (is (n/can-replace d 0 1 [(s/text b/schema "ignored") (b/p)] 1 2))
    (is (n/can-replace-with p 0 1 (get-in b/schema [:nodes "image"])))
    (is (not (n/can-replace-with d 0 1 (get-in b/schema [:nodes "paragraph"]) [(s/mark b/schema "em")]))))
    (is (n/can-append p (b/p)))
    (is (not (n/can-append p (b/hr))))
    (is (n/can-append d (b/doc (b/hr))))))

(deftest fragment-json
  (is (nil? (f/to-json [])))
  (is (= [] (f/from-json b/schema nil)))
  (is (f/eq [(b/p "x")] (f/from-json b/schema (f/to-json [(b/p "x")]))))
  (is (thrown? clojure.lang.ExceptionInfo (f/from-json b/schema {}))))
