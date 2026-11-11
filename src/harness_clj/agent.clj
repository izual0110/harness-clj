(ns harness-clj.agent
  (:require [clojure.data.json :as json]
            [harness-clj.agent-log :as agent-log]
            [harness-clj.chat-model :as chat-model]
            [harness-clj.tools :as tools]))

(def system-prompt "You are Harness, a coding agent that works in a local repository. Use the available tools to inspect files when needed. Answer concisely.")

(defn create-agent [{:keys [client system-prompts tools max-tool-rounds]
                     :or {max-tool-rounds 10}}]
  (when-not (and (integer? max-tool-rounds) (pos? max-tool-rounds))
    (throw (ex-info ":max-tool-rounds must be a positive integer"
                    {:max-tool-rounds max-tool-rounds})))
  {:client client
   :system-prompts system-prompts
   :tools tools
   :max-tool-rounds max-tool-rounds
   :state (atom
           {:history []
            :tool-calls 0})})

;;replace to defonce
(def ai-agent
  (create-agent
   {:client (chat-model/client)
    :system-prompts [{:role "system" :content system-prompt}]
    :tools (tools/specs)}))

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
        state (:state agent)
        messages (into (:system-prompts agent) (:history @state))
        response (-> (chat-model/chat (:client agent) (agent-log/request messages) (:tools agent))
                     (agent-log/response))]
    (if (not (empty? (get-in response [:message :tool_calls])))
      (loop [tools (get-in response [:message :tool_calls])] 
        (if (empty? tools) 
          nil
          (let [tool (first tools)
                _ (println "debug" tool)
                tool-name (get-in tool [:function :name])
                tool-call-id (get tool :id)
                result (tools/execute-tool tool) ] 
            (add-tool-message agent tool-call-id tool-name result)
            (recur (next tools)))))
      (add-assistant-message agent (get-in response [:message :content])))
    response))



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