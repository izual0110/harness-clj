(ns harness-clj.agent
  (:require [harness-clj.agent-log :as agent-log]
            [harness-clj.chat-model :as chat-model]
            [harness-clj.tools :as tools]
            [harness-clj.utils :refer [error]]
            [harness-clj.workspace-guard :as workspace-guard]))

(def system-prompt "You are Harness, a coding agent that works in a local repository. Use the available tools to inspect files when needed. Answer concisely.")

(defn create-agent [{:keys [client system-prompts tools guard max-turns]
                     :or {max-turns 10}}]
  (when-not (and (integer? max-turns) (pos? max-turns))
    (throw (ex-info ":max-turns must be a positive integer"
                    {:max-turns max-turns})))
  {:client client
   :system-prompts system-prompts
   :tools tools
   :max-turns max-turns
   :guard guard
   :state (atom
           {:history []
            :tool-calls 0})})

;;replace to defonce
(def ai-agent
  (let [guard (workspace-guard/create-guard ".")]
    (create-agent
     {:client (chat-model/client)
      :system-prompts [{:role "system" :content system-prompt}]
      :guard guard
      :tools (tools/create-tools guard)})))

(defn- append-message [agent message]
  (swap! (:state agent) update :history conj message))

(defn add-message [agent message]
  (append-message agent {:role "user" :content message}))

(defn add-tool-message [agent tool-call-id tool-name message]
  (if (nil? tool-call-id)
    (append-message agent {:role "tool" :tool_name tool-name :content message})
    (append-message agent {:role "tool" :tool_call_id tool-call-id :tool_name tool-name :content message})))

(defn add-assistant-message [agent message]
  (append-message agent {:role "assistant" :content message}))

(defn cleanup-messages [agent]
  (swap! (:state agent) assoc :history []))

(defn- execute-tool-call [agent tool-call]
  (let [tool-name (get-in tool-call [:function :name])
        tool-call-id (:id tool-call)
        execute-tool (get-in agent [:tools :exec])
        result (execute-tool tool-call)]
    (agent-log/tool tool-call result)
    (add-tool-message agent tool-call-id tool-name (:content result))))

(defn send-message [user-message]
  (let [agent ai-agent
        state (:state agent)]
    (add-message agent user-message)
    (loop [remaining-turns (:max-turns agent)]
      (if (zero? remaining-turns)
        (do
          (agent-log/turn-limit-reached (:max-turns agent))
          (error "Достигнут предел в " (:max-turns agent) " итераций — задача не завершена."))
        (let [messages (into (:system-prompts agent) (:history @state))
              response (-> (chat-model/chat (:client agent)
                                            (agent-log/request messages)
                                            (get-in agent [:tools :specs] ))
                           (agent-log/response))
              message (:message response)
              tool-calls (:tool_calls message)]
          (if (seq tool-calls)
            (do
              (doseq [tool-call tool-calls]
                (execute-tool-call agent tool-call))
              (recur (dec remaining-turns)))
            (add-assistant-message agent (:content message))))))))

(comment

  (let [agent ai-agent
        state (:state agent)
        messages (into (:system-prompts agent) (:history @state))]
    messages)

  (cleanup-messages ai-agent)
  (send-message "какая версия Java указана в проекте. Ответь одним предложением")
  (send-message "каких знаменитостей ты знаешь? напиши коротко top 10")
  (send-message "что ответил бы оптимистичный инженер на вопрос: \" Что делать, если в проекте не хватает зависимостей?\" Ответь максимально коротко."))