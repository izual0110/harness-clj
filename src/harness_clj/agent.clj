(ns harness-clj.agent
  (:require [harness-clj.chat-model :as chat-model]))

(def system-prompt "You are Harness, a coding agent that works in a local repository. Answer concisely.")

(defn create-agent [{:keys [client system-prompts tools]}]
  {:client client
   :system-prompts system-prompts
   :tools (into {} (map (juxt :name identity) tools))
   :state (atom
            {:history []
             :tool-calls 0})})

(defonce ai-agent
  (create-agent
    {:client (chat-model/client)
     :system-prompts [{:role "system" :content system-prompt}]
     :tools []}))

(defn add-message [agent message] (swap! (:state agent) update :history conj {:role "user" :content message}))
(defn add-assistant-message [agent message] (swap! (:state agent) update :history conj {:role "assistant" :content message}))


(let [agent ai-agent
      state (:state agent)
      messages (into (:system-prompts agent) (:history @state))]
  messages)

(defn send-message [user-message]
  (let [agent ai-agent
        _ (add-message agent user-message)
        state (:state agent)
        messages (into (:system-prompts agent) (:history @state))
        response (chat-model/chat (:client agent) messages)]
    (add-assistant-message agent (get-in response [:message :content]))
    response))

;;(send-message "повтори")

;;(send-message "что ответил бы оптимистичный инженер на вопрос: \" Что делать, если в проекте не хватает зависимостей?\" Ответь максимально коротко.")
