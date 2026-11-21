(ns harness-clj.agent
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [harness-clj.agent-log :as agent-log]
            [harness-clj.chat-model :as chat-model]
            [harness-clj.tools.tools :as tools]
            [harness-clj.tools.workspace-guard :as workspace-guard]
            [harness-clj.utils :refer [error]]))

(def system-prompt "You are Harness, a coding agent that works in a local repository. Use the available tools to inspect files when needed.
                    Follow UTR (Russian) and ASD-STE100 (English) principles: clarity, brevity, unambiguous language, simple sentences, and consistent terminology.
                    Answer concisely.")

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
            :tool-calls 0
            :context-tokens nil})})

(defn load-config
  "Reads Ollama configuration from a classpath EDN resource."
  ([] (load-config "harness.edn"))
  ([resource-name]
   (if-let [resource (io/resource resource-name)]
     (edn/read-string (slurp resource))
     (throw (ex-info "Ollama configuration resource not found"
                     {:resource resource-name})))))

;;replace to defonce
(def ai-agent
  (let [config (load-config)
        guard (workspace-guard/create-guard (:base-dir config))]
    (create-agent
     {:client (chat-model/create-client config)
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
  (append-message agent
                  (cond-> (assoc message :role "assistant")
                    (not (seq (:tool_calls message))) (dissoc :tool_calls))))

(defn cleanup-messages [agent]
  (swap! (:state agent) assoc :history [] :context-tokens nil))

(defn- estimate-context-tokens [messages]
  (let [characters (reduce + 0 (map (comp count str :content) messages))]
    (quot (+ characters 3) 4)))

(defn- response-context-tokens [{:keys [prompt_eval_count eval_count]}]
  (when (and (nat-int? prompt_eval_count) (nat-int? eval_count))
    (+ prompt_eval_count eval_count)))

(defn- measure-ms [f]
  (let [started (System/nanoTime)
        result (f)]
    [result (quot (- (System/nanoTime) started) 1000000)]))

(defn- execute-tool-call [agent tool-call]
  (let [tool-name (get-in tool-call [:function :name])
        tool-call-id (:id tool-call)
        execute-tool (get-in agent [:tools :exec-fn])
        [result elapsed-ms] (measure-ms #(execute-tool tool-call))]
    (agent-log/tool tool-call result elapsed-ms)
    (add-tool-message agent tool-call-id tool-name (:content result))))

(defn- run-turn [agent]
  (let [state (:state agent)
        {:keys [history context-tokens]} @state
        messages (into (:system-prompts agent) history)]
    (agent-log/request messages (or context-tokens (estimate-context-tokens messages)))
    (let [[response elapsed-ms] (measure-ms #(chat-model/chat (:client agent) messages
                                                            (get-in agent [:tools :specs])))
          message (:message response)]
      (when-let [tokens (response-context-tokens response)]
        (swap! state assoc :context-tokens tokens))
      (agent-log/response response elapsed-ms)
      (add-assistant-message agent message)
      (doseq [tool-call (:tool_calls message)]
        (execute-tool-call agent tool-call))
      message)))

(defn send-message [user-message]
  (let [agent ai-agent
        max-turns (:max-turns agent)]
    (add-message agent user-message)
    (loop [remaining-turns max-turns]
      (if (zero? remaining-turns)
        (do
          (agent-log/turn-limit-reached max-turns)
          (error "Достигнут предел в " max-turns " итераций — задача не завершена."))
        (let [message (run-turn agent)]
          (if (seq (:tool_calls message))
            (recur (dec remaining-turns))
            (:content message)))))))

(comment

  (let [agent ai-agent
        state (:state agent)
        messages (into (:system-prompts agent) (:history @state))]
    messages)
  
  (->> ai-agent
       (:client)
       (chat-model/list-models))

  (cleanup-messages ai-agent)
  (send-message "какая версия Java указана в проекте. Ответь одним предложением")
  (send-message "повтори")
  (send-message "каких знаменитостей ты знаешь? напиши коротко top 10")
  (send-message "что ответил бы оптимистичный инженер на вопрос: \" Что делать, если в проекте не хватает зависимостей?\" Ответь максимально коротко."))
