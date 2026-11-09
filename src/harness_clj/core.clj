(ns harness-clj.core
  (:require [clojure.string :as str]
            [harness-clj.agent-log :as agent-log])
  (:gen-class))

(defn greet
  ([] (greet "world"))
  ([name] (str "Hello, " name "!")))


(defn -main [& args]
  (agent-log/debug "Starting application")
  (println (if (seq args)
             (greet (str/join " " args))
             (greet))))

(greet)