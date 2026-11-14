(ns harness-clj.test-runner
  (:require [clojure.test :as test]
            [harness-clj.agent-test]
            [harness-clj.core-test]
            [harness-clj.chat-model-test]
            [harness-clj.tools-test]
            [harness-clj.tools.glob-test]
            [harness-clj.tools.read-test]
            [harness-clj.tools.workspace-guard-test]))

(defn -main [& _args]
  (let [{:keys [fail error]} (test/run-tests 'harness-clj.agent-test
                                          'harness-clj.core-test
                                          'harness-clj.chat-model-test
                                          'harness-clj.tools-test
                                          'harness-clj.tools.glob-test
                                          'harness-clj.tools.read-test
                                          'harness-clj.tools.workspace-guard-test)]
    (when (pos? (+ fail error))
      (System/exit 1))))
