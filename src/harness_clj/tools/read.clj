(ns harness-clj.tools.read
  (:require [clojure.string :as str]
            [harness-clj.tools.file-utils :refer [directory? file-exists]]
            [harness-clj.utils :refer [error ok]]
            [harness-clj.workspace-guard :as workspace-guard])
  (:import [java.io IOException]
           [java.nio.charset MalformedInputException]
           [java.nio.file Files]))

(def ^:private default-limit 2000)

(defn- read-file [workspace {:keys [file-path offset limit]}]
  (let [path (workspace-guard/resolve workspace file-path)]
    (cond
      (:isError path) path
      (not (file-exists path)) (error "файл не найден: " file-path)
      (directory? path) (error file-path " — это папка, а не файл")
      :else
      (try
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
                                   (format "%6d\t%s" line-number (nth lines (dec line-number))))
                                 (range from (inc to))))))))
        (catch MalformedInputException _ (error "файл не текстовый: " file-path))
        (catch IOException e (error "не удалось прочитать " file-path ": " (.getMessage e)))))))

(defn create-read-tool [guard]
  {:description (str "Reads a file from the local filesystem. Use this instead of guessing file contents.\n\n"
                     "Usage:\n"
                     "- file-path is relative to the project root\n"
                     "- Output is in `cat -n` format: line number, tab, then the line content\n"
                     "- Reads " default-limit " lines by default; use offset and limit for larger files\n"
                     "- Returns an error message if the file does not exist")
   :parameters {:type "object"
                :properties {:file-path {:type "string" :description "Path to the file, relative to the project root"}
                             :offset {:type "integer" :description "1-based line number to start from"}
                             :limit {:type "integer" :description (str "How many lines to read, default " default-limit)}}
                :required ["file-path"]}
   :call (partial read-file guard)})
