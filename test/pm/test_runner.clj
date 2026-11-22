(ns pm.test-runner
  (:require [clojure.test :as test]))

(defn -main [& namespaces]
  (let [namespaces (mapv symbol (or (seq namespaces) ["pm.model.content-test" "pm.test-builder-test"]))]
    (doseq [n namespaces] (require n))
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (shutdown-agents)
      (when (pos? (+ fail error)) (System/exit 1)))))
