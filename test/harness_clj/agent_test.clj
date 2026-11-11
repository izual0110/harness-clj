(ns harness-clj.agent-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.agent :as agent]
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
      (is (= (first responses) (agent/send-message "Hello")))
      (is (= (second responses) (agent/send-message "What next?"))))
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
          (is (= response (agent/send-message "Hello"))))
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
                                  "1\t# Project"))}
        tool-specs (tools/specs registry)
        ai-agent (agent/create-agent {:client ::client
                                     :system-prompts [{:role "system" :content "Be brief"}]
                                     :tools tool-specs})
        call (assoc (read-call {:file-path "README.md"}) :id "call-read" :type "function")
        response {:message {:role "assistant" :content "I'll read it." :tool_calls [call]}}
        requests (atom [])
        user {:role "user" :content "Describe this project"}]
    (with-redefs [agent/ai-agent ai-agent
                  tools/tools registry
                  chat-model/chat (recording-chat requests [response])]
      (is (= response (agent/send-message (:content user)))))
    (is (= [{:file-path "README.md"}] @executions))
    (is (= [{:client ::client :tools tool-specs
             :messages [{:role "system" :content "Be brief"} user]}]
           @requests))
    (is (= [{:role "tool" :tool_name "Read" :tool_call_id "call-read"
             :content (json/write-str "1\t# Project")}]
           (filterv #(= "tool" (:role %)) (:history @(:state ai-agent)))))))

(deftest multiple-tool-calls-test
  (let [executions (atom [])
        registry {"Read" (assoc read-definition :call
                                (fn [{:keys [file-path]}]
                                  (swap! executions conj file-path)
                                  {:path file-path :content "Привет"}))}
        ai-agent (agent/create-agent {:client ::client :system-prompts []
                                     :tools (tools/specs registry)})
        response {:message {:role "assistant" :content ""
                            :tool_calls (mapv #(read-call {:file-path %})
                                              ["one.clj" "two.clj" "three.clj"])}}]
    (with-redefs [agent/ai-agent ai-agent
                  tools/tools registry
                  chat-model/chat (recording-chat (atom []) [response])]
      (is (= response (agent/send-message "Read three files"))))
    (is (= ["one.clj" "two.clj" "three.clj"] @executions))
    (let [messages (filterv #(= "tool" (:role %)) (:history @(:state ai-agent)))]
      (is (= ["Read" "Read" "Read"] (mapv :tool_name messages)))
      (is (= [{:path "one.clj" :content "Привет"}
              {:path "two.clj" :content "Привет"}
              {:path "three.clj" :content "Привет"}]
             (mapv #(json/read-str (:content %) :key-fn keyword) messages))))))

(deftest unknown-tool-message-test
  (let [ai-agent (agent/create-agent {:client ::client :system-prompts [] :tools []})
        call {:function {:name "unknown" :arguments {}}}
        response {:message {:role "assistant" :tool_calls [call]}}]
    (with-redefs [agent/ai-agent ai-agent
                  tools/tools {}
                  chat-model/chat (recording-chat (atom []) [response])]
      (is (= response (agent/send-message "Use an unknown tool"))))
    (is (= {:role "tool" :tool_name "unknown"
            :content "нет инструмента 'unknown'. Доступны: []"}
           (last (:history @(:state ai-agent)))))))

(deftest tool-round-limit-config-test
  (is (= 10 (:max-tool-rounds (agent/create-agent {}))))
  (doseq [limit [1 3 10]]
    (is (= limit (:max-tool-rounds (agent/create-agent {:max-tool-rounds limit}))))))

(deftest invalid-tool-round-limit-test
  (doseq [limit [nil 0 -1 1.5 "10"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"positive integer"
                         (agent/create-agent {:max-tool-rounds limit})))))

(deftest default-read-tool-test
  (is (fn? (get-in tools/tools ["Read" :call])))
  (is (= (tools/specs) (:tools agent/ai-agent)))
  (is (= "Read" (get-in agent/ai-agent [:tools 0 :function :name]))))
