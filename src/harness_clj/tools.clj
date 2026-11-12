(ns harness-clj.tools
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness-clj.workspace-guard :as workspace-guard]
            [harness-clj.utils :refer [error ok]])
  (:import [java.io IOException]
           [java.nio.charset MalformedInputException]
           [java.nio.file Files LinkOption]))

(def ^:private default-limit 2000)

(defn- file-exists [path] (Files/exists path (make-array LinkOption 0)))
(defn- directory? [path] (Files/isDirectory path (make-array LinkOption 0)))

(defn- read-file [workspace {:keys [file-path offset limit]}]
  (let [path (workspace-guard/resolve workspace file-path)]
    (cond
      (:isError path) path
      (not (file-exists path)) (error "файл не найден: " file-path)
      (directory? path) (error file-path " — это папка, а не файл")
      :else (try
              (let [lines (Files/readAllLines path)
              line-count (count lines)
              from (max 1 (or offset 1))
              limit (max 1 (or limit default-limit))]
          (cond
            (zero? line-count) (ok "файл пустой")

            (> from line-count) (error "в файле всего " line-count " строк, запрошено начиная с " from)
            :else
            (let [to (min line-count (dec (+' from limit)))]
              (ok (str/join "\n"
                            (map (fn [line-number]
                                   (format "%6d\t%s" line-number
                                           (nth lines (dec line-number))))
                                 (range from (inc to))))))))
        (catch MalformedInputException _ (error "файл не текстовый: " file-path))
        (catch IOException e (error "не удалось прочитать " file-path ": "  (.getMessage e)))))))

(def tools
  {"Read"
   {:description "Reads a file from the local filesystem. Use this instead of guessing file contents.

                Usage:
                - file-path is relative to the project root
                - Output is in `cat -n` format: line number, tab, then the line content
                - Reads 2000 lines by default; use offset and limit for larger files
                - Returns an error message if the file does not exist"
    :parameters
    {:type "object"
     :properties {:file-path {:type "string" :description "Path to the file, relative to the project root"}
                  :offset {:type "integer" :description "1-based line number to start from"}
                  :limit {:type "integer" :description "How many lines to read, default 2000"}}
     :required ["file-path"]}

    :call (partial read-file (workspace-guard/create-guard "."))}})



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
       (f arguments)
       (str "нет инструмента '" name "'. Доступны: " (mapv first tools))))))
