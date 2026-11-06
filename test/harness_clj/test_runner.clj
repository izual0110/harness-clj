(ns harness-clj.test-runner
  (:require [clojure.test :as test]
            [harness-clj.core-test]))

(defn -main [& _args]
  (let [{:keys [fail error]} (test/run-tests 'harness-clj.core-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
