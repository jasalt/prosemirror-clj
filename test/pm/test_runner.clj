(ns pm.test-runner
  (:require [clojure.test :as test]))

(defn -main [& namespaces]
  (let [namespaces (mapv symbol (or (seq namespaces) ["pm.model.content-test" "pm.test-builder-test" "pm.model.node-test" "pm.model.mark-test" "pm.model.tree-test" "pm.model.fragment-test" "pm.model.schema-test" "pm.model.api-test"]))]
    (doseq [n namespaces] (require n))
    (let [{:keys [fail error]} (apply test/run-tests namespaces)]
      (shutdown-agents)
      (when (pos? (+ fail error)) (System/exit 1)))))
