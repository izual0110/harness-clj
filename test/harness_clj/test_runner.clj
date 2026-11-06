(ns harness-clj.test-runner
  (:require [clojure.test :as test]
            [harness-clj.core-test]
            [harness-clj.chat-model-test]))

(defn -main [& _args]
  (let [{:keys [fail error]} (test/run-tests 'harness-clj.core-test
                                          'harness-clj.chat-model-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
