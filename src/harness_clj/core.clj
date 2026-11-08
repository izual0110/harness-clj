(ns harness-clj.core
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:gen-class))

(defn greet
  ([] (greet "world"))
  ([name] (str "Hello, " name "!")))


(defn -main [& args]
  (log/debug "Starting application")
  (println (if (seq args)
             (greet (str/join " " args))
             (greet))))

(greet)