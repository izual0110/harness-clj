(ns harness-clj.tools.glob 
  (:require
    [clojure.string :as str]
    [harness-clj.tools.file-utils :refer [directory? files-walk ignored?
                                          is-regular-file modified-at]]
    [harness-clj.utils :refer [error ok]]
    [harness-clj.tools.workspace-guard :as workspace-guard]) 
  (:import
    [java.io IOException UncheckedIOException]
    [java.nio.file FileSystems Path PathMatcher]))

(def ^:private glob-max-results 100)
(def ^:private ignored-path-names #{".git" ".cpcache" "target"})


(defn- matcher-for [pattern]
  (let [file-system (FileSystems/getDefault)
        ^PathMatcher as-written (.getPathMatcher file-system (str "glob:" pattern))
        ^PathMatcher at-root (when (str/starts-with? pattern "**/")
                               (.getPathMatcher file-system (str "glob:" (subs pattern 3))))]
    ;; JDK glob requires a separator for **/, so also match files at the search root.
    (fn [path]
      (or (.matches as-written path)
          (and at-root (.matches at-root path))))))

(defn- glob-files [workspace {:keys [pattern path]}]
  (cond
    (nil? pattern)          (error "не указан шаблон поиска")
    (not (string? pattern)) (error "аргумент pattern должен быть строкой")
    (str/blank? pattern)    (error "не указан шаблон поиска")
    :else   (let [root (if (nil? path)
                         (:root workspace)
                         (workspace-guard/resolve workspace path))]
              (cond
                (:isError root) root
                (not (directory? root)) (error "папка не найдена: " path)
                :else
                (try
                  (let [matches? (matcher-for pattern)
                        found (with-open [walk (files-walk root)]
                                (->> (iterator-seq (.iterator walk))
                                     (filter #(is-regular-file %))
                                     (remove #(ignored? root % ignored-path-names))
                                     (filter #(matches? (.relativize ^Path root %)))
                                     (sort-by modified-at >)
                                     (take (inc glob-max-results))
                                     vec))]
                    (if (empty? found)
                      (ok "ничего не найдено по шаблону " pattern)
                      (ok (str/join "\n" (map #(str (.relativize ^Path (:root workspace) %))
                                              (take glob-max-results found)))
                          (when (> (count found) glob-max-results)
                            (str "\n… показаны первые " glob-max-results " совпадений, уточни шаблон")))))
                  (catch IllegalArgumentException e
                    (error "непонятный шаблон '" pattern "': " (.getMessage e)))
                  (catch UncheckedIOException e
                    (error "не удалось обойти папку: " (.getMessage e)))
                  (catch IOException e
                    (error "не удалось обойти папку: " (.getMessage e))))))))

(defn create-glob-tool [guard]
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
   :call (partial glob-files guard)})