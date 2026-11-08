(ns harness-clj.agent-test
  (:require [clojure.test :refer [deftest is]]
            [harness-clj.agent :as agent]
            [harness-clj.chat-model :as chat-model]))

(deftest initial-history-test
  (let [ai-agent (agent/create-agent {:client ::client
                                     :system-prompts ["Be brief"]
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

(deftest send-message-test
  (let [ai-agent (agent/create-agent {:client ::client
                                     :system-prompts ["Be brief" "Be helpful"]
                                     :tools []})
        requests (atom [])
        responses [{:model "test-model"
                    :message {:role "assistant" :content "Hi!"}
                    :done true}
                   {:model "test-model"
                    :message {:role "assistant" :content "Let's code."}
                    :done true}]]
    (with-redefs [agent/ai-agent ai-agent
                  chat-model/chat (fn [client messages]
                                    (swap! requests conj {:client client :messages messages})
                                    (nth responses (dec (count @requests))))]
      (is (= (first responses) (agent/send-message "Hello")))
      (is (= (second responses) (agent/send-message "What next?"))))
    (is (= [{:client ::client
             :messages [{:role "system" :content "Be brief"}
                        {:role "system" :content "Be helpful"}
                        {:role "user" :content "Hello"}]}
            {:client ::client
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
