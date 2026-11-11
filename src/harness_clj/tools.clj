(ns harness-clj.tools
  (:require
    [clojure.data.json :as json]))

(def tools
  {"Read"
   {:description "Reads a file from the local filesystem. Use this instead of guessing file contents.

                Usage:
                - file_path is relative to the project root
                - Output is in `cat -n` format: line number, tab, then the line content
                - Reads up to %d lines; use offset and limit for larger files
                - Returns an error message if the file does not exist"
    :parameters
    {:type "object"
     :properties {:file-path {:type "string" :description "Path to the file, relative to the project root"}
                  :offset {:type "integer" :description "1-based line number to start from"}
                  :limit {:type "integer" :description "How many lines to read, default 2000"}}
     :required ["file-path"]}

    :call (fn [a] (println (str "!!!!!!!!!!!!" a)) a)}})



(defn specs
  ([] (specs tools))
  ([tools]
   (mapv (fn [[name tool]]
           {:type "function"
            :function (assoc (dissoc tool :call) :name name)})
         tools)))

(defn execute-tool
  ([call] (execute-tool tools call))
  ([tools call]
   (let [{:keys [name arguments]} (:function call)]
     (if-let [f (get-in tools [name :call])]
       (json/write-str (f arguments))
       (str "нет инструмента '" name "'. Доступны: " (mapv first tools))))))



