(ns harness-clj.agent-test
  (:require [clojure.test :refer [deftest is testing]]
            [harness-clj.agent :as agent]
            [harness-clj.agent-log :as agent-log]
            [harness-clj.chat-model :as chat-model]
            [harness-clj.tools :as tools]))

(deftest initial-history-test
  (let [ai-agent (agent/create-agent {:client ::client
                                     :system-prompts [{:role "system" :content "Be brief"}]
                                     :tools []})]
    (is (= {:history [] :tool-calls 0} @(:state ai-agent)))))

(deftest message-history-test
  (let [ai-agent (agent/create-agent {:client ::client
                                     :system-prompts []
                                     :tools []})]
    (agent/add-message ai-agent "Hello")
    (agent/add-assistant-message ai-agent "Hi!")
    (agent/add-message ai-agent "What next?")
    (is (= [{:role "user" :content "Hello"}
            {:role "assistant" :content "Hi!"}
            {:role "user" :content "What next?"}]
           (:history @(:state ai-agent))))))

(deftest tool-message-history-test
  (let [ai-agent (agent/create-agent {:client ::client :system-prompts [] :tools []})]
    (agent/add-tool-message ai-agent "call-read" "Read" "File contents")
    (agent/add-tool-message ai-agent nil "Read" "More contents")
    (is (= [{:role "tool" :tool_call_id "call-read" :tool_name "Read"
             :content "File contents"}
            {:role "tool" :tool_name "Read" :content "More contents"}]
           (:history @(:state ai-agent))))))

(deftest cleanup-messages-test
  (let [ai-agent (agent/create-agent {:client ::client :system-prompts [] :tools []})]
    (agent/add-message ai-agent "Hello")
    (agent/add-assistant-message ai-agent "Hi!")
    (agent/cleanup-messages ai-agent)
    (is (= [] (:history @(:state ai-agent))))
    (agent/add-message ai-agent "Start again")
    (is (= [{:role "user" :content "Start again"}]
           (:history @(:state ai-agent))))))

(defn- recording-chat [requests responses]
  (fn [client messages tool-specs]
    (let [index (count @requests)]
      (swap! requests conj {:client client :messages messages :tools tool-specs})
      (nth responses index))))

(deftest send-message-test
  (let [ai-agent (agent/create-agent {:client ::client
                                     :system-prompts [{:role "system" :content "Be brief"}
                                                      {:role "system" :content "Be helpful"}]
                                     :tools []})
        requests (atom [])
        responses [{:model "test-model"
                    :message {:role "assistant" :content "Hi!"}
                    :done true}
                   {:model "test-model"
                    :message {:role "assistant" :content "Let's code."}
                    :done true}]]
    (with-redefs [agent/ai-agent ai-agent
                  chat-model/chat (recording-chat requests responses)]
      (is (= {:history [{:role "user" :content "Hello"}
                        {:role "assistant" :content "Hi!"}]
              :tool-calls 0}
             (agent/send-message "Hello")))
      (is (= {:history [{:role "user" :content "Hello"}
                        {:role "assistant" :content "Hi!"}
                        {:role "user" :content "What next?"}
                        {:role "assistant" :content "Let's code."}]
              :tool-calls 0}
             (agent/send-message "What next?"))))
    (is (= [{:client ::client
             :tools []
             :messages [{:role "system" :content "Be brief"}
                        {:role "system" :content "Be helpful"}
                        {:role "user" :content "Hello"}]}
            {:client ::client
             :tools []
             :messages [{:role "system" :content "Be brief"}
                        {:role "system" :content "Be helpful"}
                        {:role "user" :content "Hello"}
                        {:role "assistant" :content "Hi!"}
                        {:role "user" :content "What next?"}]}]
           @requests))
    (is (= [{:role "user" :content "Hello"}
            {:role "assistant" :content "Hi!"}
            {:role "user" :content "What next?"}
            {:role "assistant" :content "Let's code."}]
           (:history @(:state ai-agent))))))

(deftest empty-tool-calls-test
  (doseq [calls [nil []]]
    (testing (pr-str calls)
      (let [ai-agent (agent/create-agent {:client ::client :system-prompts [] :tools []})
            response {:message {:role "assistant" :content "Done" :tool_calls calls}}]
        (with-redefs [agent/ai-agent ai-agent
                      chat-model/chat (recording-chat (atom []) [response])]
          (is (= {:history [{:role "user" :content "Hello"}
                            {:role "assistant" :content "Done"}]
                  :tool-calls 0}
                 (agent/send-message "Hello"))))
        (is (= [{:role "user" :content "Hello"}
                {:role "assistant" :content "Done"}]
               (:history @(:state ai-agent))))))))

(def ^:private read-definition
  {:description "Read a file"
   :parameters {:type "object"
                :properties {:file-path {:type "string"}}
                :required ["file-path"]}})

(defn- read-call [arguments]
  {:function {:name "Read" :arguments arguments}})

(deftest tool-call-test
  (let [executions (atom [])
        registry {"Read" (assoc read-definition :call
                                (fn [arguments]
                                  (swap! executions conj arguments)
                                  {:isError false :content "1\t# Project"}))}
        tool-specs (tools/specs registry)
        ai-agent (agent/create-agent {:client ::client
                                     :system-prompts [{:role "system" :content "Be brief"}]
                                     :tools tool-specs})
        call (assoc (read-call {:file-path "README.md"}) :id "call-read" :type "function")
        response {:message {:role "assistant" :content "I'll read it." :tool_calls [call]}}
        final-response {:message {:role "assistant" :content "This is Project."}}
        requests (atom [])
        user {:role "user" :content "Describe this project"}]
    (with-redefs [agent/ai-agent ai-agent
                  tools/tools registry
                  chat-model/chat (recording-chat requests [response final-response])]
      (let [result (agent/send-message (:content user))]
        (is (= @(:state ai-agent) result))))
    (is (= [{:file-path "README.md"}] @executions))
    (let [tool-message {:role "tool" :tool_name "Read" :tool_call_id "call-read"
                        :content "1\t# Project"}
          initial-messages [{:role "system" :content "Be brief"} user]]
      (is (= [{:client ::client :tools tool-specs :messages initial-messages}
              {:client ::client :tools tool-specs
               :messages (conj initial-messages tool-message)}]
             @requests))
      (is (= [user tool-message (:message final-response)]
             (:history @(:state ai-agent)))))))

(deftest multiple-tool-calls-test
  (let [executions (atom [])
        registry {"Read" (assoc read-definition :call
                                (fn [{:keys [file-path]}]
                                  (swap! executions conj file-path)
                                  {:isError false :content (str file-path ": Привет")}))}
        ai-agent (agent/create-agent {:client ::client :system-prompts []
                                     :tools (tools/specs registry)})
        response {:message {:role "assistant" :content ""
                            :tool_calls (mapv #(read-call {:file-path %})
                                              ["one.clj" "two.clj" "three.clj"])}}
        final-response {:message {:role "assistant" :content "Read all three."}}
        requests (atom [])]
    (with-redefs [agent/ai-agent ai-agent
                  tools/tools registry
                  chat-model/chat (recording-chat requests [response final-response])]
      (let [result (agent/send-message "Read three files")]
        (is (= @(:state ai-agent) result))))
    (is (= 2 (count @requests)))
    (is (= ["one.clj" "two.clj" "three.clj"] @executions))
    (let [messages (filterv #(= "tool" (:role %)) (:history @(:state ai-agent)))]
      (is (= ["Read" "Read" "Read"] (mapv :tool_name messages)))
      (is (= ["one.clj: Привет" "two.clj: Привет" "three.clj: Привет"]
             (mapv :content messages)))
      (is (= (into [{:role "user" :content "Read three files"}] messages)
             (:messages (second @requests)))))))

(deftest tool-error-message-test
  (let [tool-error {:isError true :content "файл не найден: missing.clj"}
        registry {"Read" (assoc read-definition :call (constantly tool-error))}
        ai-agent (agent/create-agent {:client ::client :system-prompts []
                                     :tools (tools/specs registry)})
        call (read-call {:file-path "missing.clj"})
        response {:message {:role "assistant" :tool_calls [call]}}
        final-response {:message {:role "assistant" :content "File unavailable."}}
        requests (atom [])]
    (with-redefs [agent/ai-agent ai-agent
                  tools/tools registry
                  chat-model/chat (recording-chat requests [response final-response])]
      (let [result (agent/send-message "Read the missing file")]
        (is (= @(:state ai-agent) result))))
    (let [tool-message {:role "tool" :tool_name "Read" :content (:content tool-error)}]
      (is (= tool-message
             (first (filterv #(= "tool" (:role %)) (:history @(:state ai-agent))))))
      (is (= tool-message (last (:messages (second @requests)))))
      (is (= (:message final-response) (last (:history @(:state ai-agent))))))))

(deftest successive-tool-rounds-test
  (let [ai-agent (agent/create-agent {:client ::client :system-prompts [] :tools []
                                     :max-turns 3})
        first-call (read-call {:file-path "one.clj"})
        second-call (read-call {:file-path "two.clj"})
        responses [{:message {:role "assistant" :content "" :tool_calls [first-call]}}
                   {:message {:role "assistant" :content "" :tool_calls [second-call]}}
                   {:message {:role "assistant" :content "Done"}}]
        requests (atom [])
        executions (atom [])]
    (with-redefs [agent/ai-agent ai-agent
                  tools/execute-tool (fn [call]
                                       (swap! executions conj call)
                                       {:isError false :content (get-in call [:function :arguments :file-path])})
                  chat-model/chat (recording-chat requests responses)]
      (let [result (agent/send-message "Read both files")]
        (is (= @(:state ai-agent) result))))
    (is (= [first-call second-call] @executions))
    (is (= [1 2 3] (mapv #(count (:messages %)) @requests)))
    (is (= [{:role "user" :content "Read both files"}
            {:role "tool" :tool_name "Read" :content "one.clj"}
            {:role "tool" :tool_name "Read" :content "two.clj"}]
           (:messages (last @requests))))
    (is (= (:message (last responses))
           (last (:history @(:state ai-agent)))))))

(deftest turn-limit-test
  (doseq [limit [1 2]]
    (let [ai-agent (agent/create-agent {:client ::client :system-prompts [] :tools []
                                       :max-turns limit})
          response {:message {:role "assistant" :content ""
                              :tool_calls [(read-call {:file-path "one.clj"})
                                           (read-call {:file-path "two.clj"})]}}
          requests (atom [])
          executions (atom 0)
          limit-events (atom [])]
      (with-redefs [agent/ai-agent ai-agent
                    agent-log/turn-limit-reached #(swap! limit-events conj %)
                    tools/execute-tool (fn [_]
                                         (swap! executions inc)
                                         {:isError false :content "Contents"})
                    chat-model/chat (recording-chat requests (repeat limit response))]
        (is (= {:isError true
                :content (str "Достигнут предел в " limit " итераций — задача не завершена.")}
               (agent/send-message "Keep reading"))))
      (is (= (* 2 limit) @executions))
      (is (= limit (count @requests)))
      (is (= [limit] @limit-events))
      (is (= (inc (* 2 limit)) (count (:history @(:state ai-agent))))))))

(deftest final-answer-on-last-turn-test
  (doseq [limit [1 2]]
    (let [ai-agent (agent/create-agent {:client ::client :system-prompts [] :tools []
                                       :max-turns limit})
          tool-response {:message {:role "assistant" :content ""
                                   :tool_calls [(read-call {:file-path "one.clj"})]}}
          final-response {:message {:role "assistant" :content "Done"}}
          responses (conj (vec (repeat (dec limit) tool-response)) final-response)
          requests (atom [])
          limit-events (atom [])]
      (with-redefs [agent/ai-agent ai-agent
                    agent-log/turn-limit-reached #(swap! limit-events conj %)
                    tools/execute-tool (constantly {:isError false :content "Contents"})
                    chat-model/chat (recording-chat requests responses)]
        (let [result (agent/send-message "Read and answer")]
          (is (= @(:state ai-agent) result))
          (is (= (:message final-response) (last (:history result))))))
      (is (= limit (count @requests)))
      (is (= [] @limit-events)))))

(deftest turn-limit-config-test
  (is (= 10 (:max-turns (agent/create-agent {}))))
  (doseq [limit [1 3 10]]
    (is (= limit (:max-turns (agent/create-agent {:max-turns limit}))))))

(deftest invalid-turn-limit-test
  (doseq [limit [nil 0 -1 1.5 "10"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":max-turns must be a positive integer"
                         (agent/create-agent {:max-turns limit})))))

(deftest default-read-tool-test
  (is (fn? (get-in tools/tools ["Read" :call])))
  (is (= (tools/specs) (:tools agent/ai-agent)))
  (is (= "Read" (get-in agent/ai-agent [:tools 0 :function :name]))))
