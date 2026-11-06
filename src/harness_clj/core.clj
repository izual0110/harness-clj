(ns harness-clj.core
  (:require [clojure.string :as str])
  (:gen-class))

(defn greet
  ([] (greet "world"))
  ([name] (str "Hello, " name "!")))

(defn -main [& args]
  (println (if (seq args)
             (greet (str/join " " args))
             (greet))))
