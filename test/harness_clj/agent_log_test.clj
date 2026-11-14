(ns harness-clj.agent-log-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.agent-log :as agent-log]))

(def ^:private footer (str "\n└" (apply str (repeat 80 "─"))))

(deftest short-block-test
  (doseq [[content expected] [[nil ""]
                              ["" ""]
                              [42 "42"]
                              ["one\n\ntwo\n" "one\n│ \n│ two\n│ "]]]
    (testing (pr-str content)
      (is (= (str "┌─ title\n│ " expected footer)
             (#'agent-log/block "title" content))))))

(deftest block-newlines-test
  (doseq [newline ["\n" "\r\n" "\r"]]
    (testing (pr-str newline)
      (is (= (str "┌─ title\n│ one\n│ two" footer)
             (#'agent-log/block "title" (str "one" newline "two")))))))

(deftest block-line-limit-test
  (let [limit @#'agent-log/max-content-lines]
    (doseq [newline ["\n" "\r\n" "\r"]
            line-count [(dec limit) limit (inc limit) (+ limit 50)]]
      (testing (str line-count " lines separated by " (pr-str newline))
        (let [content (str/join newline (range 1 (inc line-count)))
              expected (str "┌─ title\n│ "
                            (str/join "\n│ " (range 1 (inc (min limit line-count))))
                            (when (> line-count limit) "\n│ ...")
                            footer)]
          (is (= expected (#'agent-log/block "title" content))))))))
(ns harness-clj.agent-log-test)