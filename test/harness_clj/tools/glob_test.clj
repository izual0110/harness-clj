(ns harness-clj.tools.glob-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.tools :as tools]
            [harness-clj.workspace-guard :as workspace-guard])
  (:import [java.io IOException UncheckedIOException]
           [java.nio.file Files FileSystem FileVisitOption Path]
           [java.nio.file.attribute BasicFileAttributes FileAttribute FileTime]
           [java.nio.file.spi FileSystemProvider]))

(defn- with-temp-directory [f]
  (let [directory (-> (Files/createTempDirectory (.toPath (io/file "."))
                                                ".glob-test-"
                                                (make-array FileAttribute 0))
                      .toAbsolutePath
                      .normalize)]
    (try
      (f directory)
      (finally
        (with-open [paths (Files/walk directory (make-array FileVisitOption 0))]
          (doseq [^Path path (reverse (iterator-seq (.iterator paths)))]
            (Files/deleteIfExists path)))))))

(defn- create-directory [^Path directory filename]
  (Files/createDirectories (.resolve directory ^String filename)
                           (make-array FileAttribute 0)))

(defn- create-file [^Path directory filename modified]
  (let [path (.resolve directory ^String filename)]
    (Files/createDirectories (.getParent path) (make-array FileAttribute 0))
    (Files/createFile path (make-array FileAttribute 0))
    (Files/setLastModifiedTime path (FileTime/fromMillis (+ 1700000000000 (* 1000 modified))))
    path))

(defn- assert-error-prefix [result prefix]
  (is (true? (:isError result)))
  (is (and (string? (:content result))
           (str/starts-with? (:content result) prefix))))

(deftest glob-registration-test
  (let [tool-set (tools/create-tools (workspace-guard/create-guard "."))
        {:keys [description parameters call] :as glob} (get-in tool-set [:tools "Glob"])
        spec (first (filter #(= "Glob" (get-in % [:function :name])) (:specs tool-set)))]
    (is (map? glob))
    (is (and (string? description) (not (str/blank? description))))
    (is (fn? call))
    (is (= "object" (:type parameters)))
    (is (= "string" (get-in parameters [:properties :pattern :type])))
    (is (= "string" (get-in parameters [:properties :path :type])))
    (is (= ["pattern"] (:required parameters)))
    (is (= {:type "function" :function (assoc (dissoc glob :call) :name "Glob")}
           spec))
    (is (not (contains? (:function spec) :call)))))

(deftest glob-public-call-test
  (with-temp-directory
    (fn [directory]
      (let [file (create-file directory "public.clj" 1)
            workspace (workspace-guard/create-guard ".")
            tool-set (tools/create-tools workspace)
            relative (.relativize ^Path (:root workspace) file)
            arguments {:pattern "*.clj" :path (str directory)}
            expected {:isError false :content (str relative "\n")}]
        (is (= expected ((get-in tool-set [:tools "Glob" :call]) arguments)))
        (is (= expected ((:exec-fn tool-set) {:function {:name "Glob" :arguments arguments}})))
        (is (= "" (with-out-str ((get-in tool-set [:tools "Glob" :call]) arguments))))))))

(deftest glob-root-and-nested-files-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (create-file directory "root.clj" 1)
        (create-file directory "src/nested.clj" 2)
        (create-file directory "src/deep/core.clj" 3)
        (create-file directory "notes.txt" 4)
        (create-directory directory "directory.clj")
        (is (= {:isError false :content "src/deep/core.clj\nsrc/nested.clj\nroot.clj\n"}
               (#'tools/glob-files workspace {:pattern "**/*.clj"})))))))

(deftest glob-jdk-patterns-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (doseq [[filename modified] [["root.clj" 1]
                                     ["Tool1.clj" 2]
                                     ["Tool2.java" 3]
                                     ["src/ToolA.clj" 4]
                                     ["src/ToolB.java" 5]
                                     ["src/deep/Tool9.clj" 6]
                                     ["src/other.txt" 7]]]
          (create-file directory filename modified))
        (doseq [[pattern expected]
                [["*.clj" "Tool1.clj\nroot.clj\n"]
                 ["**/Tool?.{clj,java}" "src/deep/Tool9.clj\nsrc/ToolB.java\nsrc/ToolA.clj\nTool2.java\nTool1.clj\n"]
                 ["src/Tool[AB].clj" "src/ToolA.clj\n"]
                 ["src/*.java" "src/ToolB.java\n"]
                 ["src/**/Tool?.clj" "src/deep/Tool9.clj\n"]]]
          (testing pattern
            (is (= {:isError false :content expected}
                   (#'tools/glob-files workspace {:pattern pattern})))))))))

(deftest glob-newest-first-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (create-file directory "m-oldest.clj" 1)
        (create-file directory "z-newest.clj" 3)
        (create-file directory "a-middle.clj" 2)
        (is (= {:isError false :content "z-newest.clj\na-middle.clj\nm-oldest.clj\n"}
               (#'tools/glob-files workspace {:pattern "*.clj"})))))))

(deftest glob-default-path-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (create-file directory "root.clj" 1)
        (doseq [options [{} {:path nil} {:path "."} {:path (str directory)}]]
          (testing (pr-str options)
            (is (= {:isError false :content "root.clj\n"}
                   (#'tools/glob-files workspace (assoc options :pattern "*.clj"))))))))))

(deftest glob-subdirectory-path-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (create-file directory "outside-search.clj" 3)
        (create-file directory "src/direct.clj" 1)
        (create-file directory "src/deep/nested.clj" 2)
        (doseq [path ["src" "./src/./" (str (.resolve ^Path directory "src"))]]
          (testing path
            (is (= {:isError false :content "src/deep/nested.clj\nsrc/direct.clj\n"}
                   (#'tools/glob-files workspace {:pattern "**/*.clj" :path path})))
            (is (= {:isError false :content "src/direct.clj\n"}
                   (#'tools/glob-files workspace {:pattern "*.clj" :path path})))))))))

(deftest glob-missing-pattern-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (doseq [options [{} {:pattern nil} {:pattern ""} {:pattern " \t\r\n"}]]
          (testing (pr-str options)
            (is (= {:isError true :content "не указан шаблон поиска"}
                   (#'tools/glob-files workspace options)))))))))

(deftest glob-missing-directory-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (create-file directory "file.clj" 1)
        (doseq [path ["missing" "missing/child" "file.clj"
                     (str (.resolve ^Path directory "missing"))
                     (str (.resolve ^Path directory "file.clj"))]]
          (testing path
            (is (= {:isError true :content (str "папка не найдена: " path)}
                   (#'tools/glob-files workspace {:pattern "**/*" :path path})))))))))

(deftest glob-invalid-path-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (doseq [path ["" " \t\n"]]
          (is (= {:isError true :content "путь не указан"}
                 (#'tools/glob-files workspace {:pattern "**/*" :path path}))))
        (assert-error-prefix (#'tools/glob-files workspace {:pattern "**/*" :path "bad\u0000path"})
                             "некорректный путь: ")))))

(deftest glob-invalid-pattern-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (doseq [pattern ["[" "{one,two" "dangling\\"]]
          (testing pattern
            (assert-error-prefix (#'tools/glob-files workspace {:pattern pattern})
                                 (str "непонятный шаблон '" pattern "': "))))))))

(deftest glob-no-matches-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)
            expected {:isError false :content "ничего не найдено по шаблону **/*.clj"}]
        (is (= expected (#'tools/glob-files workspace {:pattern "**/*.clj"})))
        (create-file directory "notes.txt" 1)
        (create-directory directory "not-a-file.clj")
        (is (= expected (#'tools/glob-files workspace {:pattern "**/*.clj"})))))))

(deftest glob-path-traversal-test
  (with-temp-directory
    (fn [directory]
      (let [root (create-directory directory "workspace")
            outside (create-directory directory "workspace-sibling")
            workspace (workspace-guard/create-guard root)]
        (create-file outside "secret.clj" 1)
        (doseq [path [".." "../workspace-sibling" "nested/../../workspace-sibling"
                     (str directory) (str outside)]]
          (testing path
            (let [result (#'tools/glob-files workspace {:pattern "**/*.clj" :path path})]
              (assert-error-prefix result "путь вне рабочей папки: ")
              (is (= (workspace-guard/resolve workspace path) result)))))))))

(deftest glob-external-symlink-path-test
  (with-temp-directory
    (fn [directory]
      (let [root (create-directory directory "workspace")
            outside (create-directory directory "outside")
            workspace (workspace-guard/create-guard root)]
        (create-file outside "secret.clj" 1)
        (Files/createSymbolicLink (.resolve root "outside-link") outside
                                 (make-array FileAttribute 0))
        (doseq [path ["outside-link" "outside-link/missing"
                     (str (.resolve root "outside-link"))]]
          (testing path
            (let [result (#'tools/glob-files workspace {:pattern "**/*.clj" :path path})]
              (assert-error-prefix result "путь ведёт наружу через симлинк: ")
              (is (= (workspace-guard/resolve workspace path) result)))))))))

(deftest glob-does-not-follow-symlinks-test
  (with-temp-directory
    (fn [directory]
      (let [root (create-directory directory "workspace")
            outside (create-directory directory "outside")
            inside-file (create-file root "data/inside.clj" 1)
            outside-file (create-file outside "secret.clj" 2)
            workspace (workspace-guard/create-guard root)]
        (create-directory root "nested")
        (doseq [[link target] [["internal-file.clj" inside-file]
                               ["external-file.clj" outside-file]
                               ["dangling.clj" (.resolve outside "missing.clj")]
                               ["nested/internal" (.resolve root "data")]
                               ["nested/external" outside]
                               ["nested/loop" root]]]
          (Files/createSymbolicLink (.resolve root ^String link) target
                                   (make-array FileAttribute 0)))
        (is (= {:isError false :content "data/inside.clj\n"}
               (#'tools/glob-files workspace {:pattern "**/*.clj"})))))))

(deftest glob-ignored-directory-segments-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)
            included ["visible.clj" "src/visible.clj" ".github/workflow.clj"
                      ".hidden/visible.clj" "targeted/visible.clj" "ignored-by-git/visible.clj"]]
        (doseq [[modified filename] (map-indexed vector included)]
          (create-file directory filename modified))
        (doseq [segment [".git" ".cpcache" "target"]
                prefix ["" "src/deep/"]]
          (create-file directory (str prefix segment "/hidden.clj") 100))
        (spit (.toFile (.resolve ^Path directory ".gitignore")) "ignored-by-git/\n")
        (is (= {:isError false :content (apply str (map #(str % "\n") (reverse included)))}
               (#'tools/glob-files workspace {:pattern "**/*.clj"})))))))

(deftest glob-walk-io-errors-test
  (with-temp-directory
    (fn [^Path directory]
      (let [workspace (workspace-guard/create-guard directory)]
        (doseq [failure [(IOException. "simulated walk failure")
                         (UncheckedIOException. (IOException. "simulated lazy walk failure"))]]
          (testing (.getSimpleName (class failure))
            (let [provider (proxy [FileSystemProvider] []
                             (readAttributes [path attribute-type options]
                               (Files/readAttributes directory BasicFileAttributes options))
                             (newDirectoryStream [path filter]
                               (throw failure)))
                  filesystem (proxy [FileSystem] []
                               (provider [] provider)
                               (getPathMatcher [pattern]
                                 (.getPathMatcher (.getFileSystem directory) pattern)))
                  failing-path (reify Path
                                 (getFileSystem [_] filesystem))]
              (with-redefs [workspace-guard/resolve (fn [_ _] failing-path)]
                (assert-error-prefix (#'tools/glob-files workspace {:pattern "**/*" :path "."})
                                     "не удалось обойти папку: ")))))))))

(deftest glob-exact-result-limit-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)
            filenames (mapv #(format "%03d.clj" %) (range 100))]
        (is (= 100 @#'tools/glob-max-results))
        (doseq [[modified filename] (map-indexed vector filenames)]
          (create-file directory filename modified))
        (is (= {:isError false :content (apply str (map #(str % "\n") (reverse filenames)))}
               (#'tools/glob-files workspace {:pattern "*.clj"})))))))

(deftest glob-exceeded-result-limit-test
  (with-temp-directory
    (fn [directory]
      (let [workspace (workspace-guard/create-guard directory)
            filenames (mapv #(format "%03d.clj" %) (range 101))
            warning "… показаны первые 100 совпадений, уточни шаблон"]
        (doseq [[modified filename] (map-indexed vector filenames)]
          (create-file directory filename modified))
        (let [result (#'tools/glob-files workspace {:pattern "*.clj"})
              lines (str/split-lines (:content result))
              paths (vec (butlast lines))]
          (is (false? (:isError result)))
          (is (= 101 (count lines)))
          (is (= warning (last lines)))
          (is (str/ends-with? (:content result) warning))
          (is (= 100 (count (set paths))))
          (is (every? (set filenames) paths))
          (is (= (sort #(compare %2 %1) paths) paths)))))))
