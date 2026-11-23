(ns pm.pins-test
  "PORTED.edn must name the exact upstream versions the differential harness runs
  against. (The consuming client's package.json is checked in hyper-prosemirror
  once that bundle exists.)"
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]))

(deftest ported-versions-match-differential-pins
  (let [ported (edn/read-string (slurp "PORTED.edn"))
        pins (:dependencies (json/read-str (slurp "diff/package.json") :key-fn keyword))
        ported-version (fn [pkg] (get-in ported [:packages (symbol pkg) :version]))]
    (doseq [pkg ["prosemirror-model" "prosemirror-transform" "prosemirror-schema-basic" "prosemirror-schema-list"]]
      (is (= (ported-version pkg) (get pins (keyword pkg))) (str pkg " pin vs PORTED.edn")))))
