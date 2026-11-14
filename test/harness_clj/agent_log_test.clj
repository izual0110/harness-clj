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

(deftest block-does-not-truncate-test
  (let [lines (range 1 (+ @#'agent-log/max-content-lines 50))]
    (doseq [newline ["\n" "\r\n" "\r"]]
      (is (= (str "┌─ title\n│ " (str/join "\n│ " lines) footer)
             (#'agent-log/block "title" (str/join newline lines)))))))

(deftest message-content-test
  (doseq [[message expected] [[{} ""]
                              [{:content nil} ""]
                              [{:content 42} "42"]
                              [{:content "one\r\n\rthree\n"} "one\n\nthree\n"]
                              [{:content "Done" :tool_calls []} "Done"]]]
    (is (= expected (#'agent-log/message-content message))))
  (let [calls [{:function {:name "Read" :arguments {:file-path "README.md"}}}]]
    (doseq [content [nil "" "Reading"]]
      (is (= (str content (when (seq content) "\n") "Tool calls: " (pr-str calls))
             (#'agent-log/message-content {:content content :tool_calls calls}))))))

(deftest message-content-line-limit-test
  (let [limit @#'agent-log/max-content-lines]
    (doseq [newline ["\n" "\r\n" "\r"]
            line-count [(dec limit) limit (inc limit) (+ limit 50)]]
      (testing (str line-count " lines separated by " (pr-str newline))
        (let [content (str/join newline (range 1 (inc line-count)))
              expected (str (str/join "\n" (range 1 (inc (min limit line-count))))
                            (when (> line-count limit) "\n..."))]
          (is (= expected (#'agent-log/message-content {:content content}))))))))

(deftest request-truncates-each-message-test
  (let [limit @#'agent-log/max-content-lines
        first-lines (map #(str "first-" %) (range 1 (+ limit 3)))
        second-lines (map #(str "second-" %) (range 1 (+ limit 3)))
        messages [{:role "tool" :tool_name "Read" :content (str/join "\n" first-lines)}
                  {:role "tool" :tool_name "Read" :content (str/join "\n" second-lines)}
                  {:role "assistant" :content "Done"}]
        logged (atom nil)]
    (with-redefs-fn {#'agent-log/block (fn [title content]
                                       (reset! logged {:title title :content content})
                                       "")}
      #(is (identical? messages (agent-log/request messages))))
    (is (= {:title "context · messages: 3"
            :content (str "[tool][Read]\n" (str/join "\n" (take limit first-lines)) "\n...\n"
                          "[tool][Read]\n" (str/join "\n" (take limit second-lines)) "\n...\n"
                          "[assistant] Done")}
           @logged))))

(deftest tool-truncates-content-test
  (let [limit @#'agent-log/max-content-lines
        result {:isError false :content (str/join "\n" (range 1 (+ limit 3)))}
        logged (atom nil)]
    (with-redefs-fn {#'agent-log/block (fn [_ content]
                                       (reset! logged content)
                                       "")}
      #(agent-log/tool {:function {:name "Read" :arguments {}}} result))
    (is (= (str (str/join "\n" (range 1 (inc limit))) "\n...") @logged))))
(ns harness-clj.agent-log-test)