(ns harness-clj.agent
  (:require [harness-clj.chat-model :as chat-model]))

(def system-prompt "You are Harness, a coding agent that works in a local repository. Answer concisely.")

(defn create-agent [{:keys [client system-prompts tools]}]
  {:client client
   :system-prompts system-prompts
   :tools (into {} (map (juxt :name identity) tools))
   :state (atom
            {:histories {}
             :tool-calls 0})})

(defonce ai-agent
  (create-agent
    {:client (chat-model/client)
     :system-prompts [system-prompt]
     :tools []}))

;;(def client (chat-model/client))

(println (chat-model/generate (:client ai-agent) "что ответил бы оптимистичный инженер на вопрос: \" Что делать, если в проекте не хватает зависимостей?\""))


