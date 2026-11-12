(ns harness-clj.agent
  (:require [harness-clj.agent-log :as agent-log]
            [harness-clj.chat-model :as chat-model]
            [harness-clj.tools :as tools]
            [harness-clj.utils :refer [error]]))

(def system-prompt "You are Harness, a coding agent that works in a local repository. Use the available tools to inspect files when needed. Answer concisely.")

(defn create-agent [{:keys [client system-prompts tools max-turns]
                     :or {max-turns 10}}]
  (when-not (and (integer? max-turns) (pos? max-turns))
    (throw (ex-info ":max-turns must be a positive integer"
                    {:max-turns max-turns})))
  {:client client
   :system-prompts system-prompts
   :tools tools
   :max-turns max-turns
   :state (atom
           {:history []
            :tool-calls 0})})

;;replace to defonce
(def ai-agent
  (create-agent
   {:client (chat-model/client)
    :system-prompts [{:role "system" :content system-prompt}]
    :tools (tools/specs)}))

;;ai-agent

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

(defn cleanup-messages [agent] (swap! (:state agent) assoc :history []))

(defn send-message [user-message]
  (let [agent ai-agent
        _ (add-message agent user-message)
        state (:state agent)]
    (loop [max-turns (:max-turns agent)]
      (if (= 0 max-turns)
        (do (agent-log/turn-limit-reached (:max-turns agent))
            (error "Достигнут предел в " (:max-turns agent) " итераций — задача не завершена."))
        (let [messages (into (:system-prompts agent) (:history @state))
              response (-> (chat-model/chat (:client agent) (agent-log/request messages) (:tools agent))
                           (agent-log/response))]
          (if (not (empty? (get-in response [:message :tool_calls])))
            (do (doseq [tool (get-in response [:message :tool_calls])
                        :let [_ (println "debug" tool)
                              tool-name (get-in tool [:function :name])
                              tool-call-id (get tool :id)
                              result (tools/execute-tool tool)]]
                  (agent-log/tool tool result)
                  (add-tool-message agent tool-call-id tool-name (:content result)))
                (recur (dec max-turns)))
            (add-assistant-message agent (get-in response [:message :content]))))))))



(comment

  (let [agent ai-agent
        state (:state agent)
        messages (into (:system-prompts agent) (:history @state))]
    messages)

  (cleanup-messages ai-agent)
  (send-message "какая версия Java указана в проекте. Ответь одним предложением")
  (send-message "каких знаменитостей ты знаешь? напиши коротко top 10")
  (send-message "что ответил бы оптимистичный инженер на вопрос: \" Что делать, если в проекте не хватает зависимостей?\" Ответь максимально коротко.")
)