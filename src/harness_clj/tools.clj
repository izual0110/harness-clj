(ns harness-clj.tools
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [harness-clj.workspace-guard :as workspace-guard]
            [harness-clj.utils :refer [error ok]])
  (:import [java.io IOException UncheckedIOException]
           [java.nio.charset MalformedInputException]
           [java.nio.file Files FileSystems FileVisitOption LinkOption Path PathMatcher]))

(def ^:private default-limit 2000)
(def ^:private glob-max-results 100)
(def ^:private ignored-path-names #{".git" ".cpcache" "target"})

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

(defn- matcher-for [pattern]
  (let [file-system (FileSystems/getDefault)
        ^PathMatcher as-written (.getPathMatcher file-system (str "glob:" pattern))
        ^PathMatcher at-root (when (str/starts-with? pattern "**/")
                              (.getPathMatcher file-system (str "glob:" (subs pattern 3))))]
    ;; JDK glob requires a separator for **/, so also match files at the search root.
    (fn [path]
      (or (.matches as-written path)
          (and at-root (.matches at-root path))))))

(defn- ignored? [^Path root ^Path path]
  (some #(contains? ignored-path-names (str %)) (.relativize root path)))

(defn- modified-at [^Path path]
  (.toMillis (Files/getLastModifiedTime path (make-array LinkOption 0))))

(defn- glob-files [workspace {:keys [pattern path]}]
  (if (str/blank? pattern)
    (error "не указан шаблон поиска")
    (let [root (if (nil? path)
                 (:root workspace)
                 (workspace-guard/resolve workspace path))]
      (cond
        (:isError root) root
        (not (directory? root)) (error "папка не найдена: " path)
        :else
        (try
          (let [matches? (matcher-for pattern)
                found (with-open [walk (Files/walk ^Path root (make-array FileVisitOption 0))]
                        (->> (iterator-seq (.iterator walk))
                             (filter #(Files/isRegularFile % (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))
                             (remove #(ignored? root %))
                             (filter #(matches? (.relativize ^Path root %)))
                             (sort-by modified-at >)
                             (take (inc glob-max-results))
                             vec))]
            (if (empty? found)
              (ok "ничего не найдено по шаблону " pattern)
              (ok (str (apply str (map #(str (.relativize ^Path (:root workspace) %) "\n")
                                      (take glob-max-results found)))
                       (when (> (count found) glob-max-results)
                         (str "… показаны первые " glob-max-results " совпадений, уточни шаблон"))))))
          (catch IllegalArgumentException e
            (error "непонятный шаблон '" pattern "': " (.getMessage e)))
          (catch UncheckedIOException e
            (error "не удалось обойти папку: " (.getMessage e)))
          (catch IOException e
            (error "не удалось обойти папку: " (.getMessage e))))))))

(defn- specs [tools]
  (mapv (fn [[name tool]]
          {:type "function"
           :function (assoc (dissoc tool :call) :name name)})
        tools))

(defn- execute-tool
  [tools call] 
  (let [{:keys [name arguments]} (:function call)]
    (if-let [f (get-in tools [name :call])]
      (f arguments)
      (str "нет инструмента '" name "'. Доступны: " (mapv first tools)))))

(defn create-tools [guard]
  (let [tools {"Read"
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
              
                :call (partial read-file guard)}
              
               "Glob"
               {:description (str "Finds files by name pattern. Use this when you know what a file is called but not where it is.
                                          Usage:
                                          - Supports patterns like \"**/*.java\" or \"**/Tool*.java\"
                                          - Returns paths relative to the project root, most recently modified first
                                          - Skips .git, .cpcache, target, and symbolic links
                                          - Returns at most " glob-max-results " results")
                :parameters
                {:type "object"
                 :properties {:pattern {:type "string" :description "Glob pattern, for example **/*.java or **/Tool*.java"}
                              :path {:type "string" :description "Directory to search in, defaults to project root"}}
                 :required ["pattern"]}
              
                :call (partial glob-files guard)}
               
               }]
                {:guard guard
                 :tools tools
                 :specs (specs tools)
                 :exec-fn (partial execute-tool tools)}))



