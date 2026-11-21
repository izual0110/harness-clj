(ns harness-clj.tools.workspace-guard-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.tools.workspace-guard :as guard])
  (:import [java.io IOException]
           [java.nio.file FileSystem FileVisitOption Files LinkOption Path Paths]
           [java.nio.file.attribute BasicFileAttributes FileAttribute]
           [java.nio.file.spi FileSystemProvider]))

(defn- with-temp-directory [f]
  (let [directory (.normalize
                   (.toAbsolutePath
                    (Files/createTempDirectory "harness-clj-workspace-guard-test-"
                                               (make-array FileAttribute 0))))]
    (try
      (f directory)
      (finally
        (with-open [paths (Files/walk directory (make-array FileVisitOption 0))]
          (doseq [^Path path (reverse (iterator-seq (.iterator paths)))]
            (Files/deleteIfExists path)))))))

(defn- assert-error [result message]
  (is (map? result))
  (is (true? (:isError result)))
  (is (string? (:content result)))
  (when (string? (:content result))
    (is (str/includes? (:content result) message))))

(deftest create-guard-test
  (with-temp-directory
    (fn [^Path root]
      (let [cwd (.normalize (.toAbsolutePath (Paths/get "" (make-array String 0))))
            relative (.relativize cwd root)
            unnormalized (.resolve root "unused/..")]
        (doseq [[label input] [["String" (str root)]
                               ["Path" root]
                               ["File" (.toFile root)]
                               ["relative String" (str relative)]
                               ["relative Path" relative]
                               ["relative File" (.toFile relative)]
                               ["unnormalized String" (str unnormalized)]
                               ["unnormalized Path" unnormalized]
                               ["unnormalized File" (.toFile unnormalized)]]]
          (testing label
            (let [workspace (guard/create-guard input)
                  result (:root workspace)]
              (is (map? workspace))
              (is (instance? Path result))
              (is (= root result))
              (when (instance? Path result)
                (is (.isAbsolute ^Path result))
                (is (= result (.normalize ^Path result)))))))))))

(deftest resolve-paths-test
  (with-temp-directory
    (fn [^Path root]
      (let [workspace (guard/create-guard root)
            directory (Files/createDirectory (.resolve root "каталог")
                                             (make-array FileAttribute 0))
            file (Files/createFile (.resolve root "файл λ.txt")
                                   (make-array FileAttribute 0))
            nested-file (Files/createFile (.resolve directory "данные.txt")
                                          (make-array FileAttribute 0))
            missing-file (.resolve root "новая/папка/файл.txt")]
        (doseq [[raw-path expected] [["." root]
                                     ["каталог/.." root]
                                     [(str root) root]
                                     ["файл λ.txt" file]
                                     ["./каталог/../файл λ.txt" file]
                                     [(str (.resolve root "каталог/../файл λ.txt")) file]
                                     ["каталог/данные.txt" nested-file]
                                     ["./каталог/./данные.txt" nested-file]
                                     [(str nested-file) nested-file]
                                     ["новая/папка" (.resolve root "новая/папка")]
                                     ["новая/папка/файл.txt" missing-file]
                                     ["новая/лишняя/../папка/файл.txt" missing-file]
                                     [(str missing-file) missing-file]]]
          (testing raw-path
            (is (= expected (guard/resolve workspace raw-path)))))))))

(deftest invalid-path-test
  (with-temp-directory
    (fn [^Path root]
      (let [workspace (guard/create-guard root)]
        (doseq [[raw-path message] [[nil "путь не указан"]
                                    ["" "путь не указан"]
                                    [" \n\t\r" "путь не указан"]
                                    [42 "путь не указан"]
                                    [["file.txt"] "путь не указан"]
                                    [{:path "file.txt"} "путь не указан"]
                                    [(.resolve root "file.txt") "путь не указан"]
                                    [(.toFile root) "путь не указан"]
                                    ["\u0000" "некорректный путь"]
                                    ["bad\u0000path" "некорректный путь"]
                                    ["nested/bad\u0000path" "некорректный путь"]
                                    [(str root "/bad\u0000path") "некорректный путь"]]]
          (testing (pr-str raw-path)
            (assert-error (guard/resolve workspace raw-path) message)))))))

(deftest path-traversal-test
  (with-temp-directory
    (fn [^Path directory]
      (let [root (Files/createDirectory (.resolve directory "workspace")
                                       (make-array FileAttribute 0))
            outside (Files/createDirectory (.resolve directory "outside")
                                          (make-array FileAttribute 0))
            sibling (Files/createDirectory (.resolve directory "workspace-sibling")
                                          (make-array FileAttribute 0))
            file (Files/createFile (.resolve root "inside.txt")
                                   (make-array FileAttribute 0))
            workspace (guard/create-guard root)]
        (testing "normalized paths outside the root are rejected, including sibling prefixes"
          (doseq [raw-path [".."
                           "../outside/file.txt"
                           "nested/../../outside.txt"
                           "../workspace-sibling/file.txt"
                           (str directory)
                           (str outside)
                           (str (.resolve outside "secret.txt"))
                           (str (.resolve root "../outside/file.txt"))
                           (str sibling)
                           (str (.resolve sibling "file.txt"))]]
            (testing raw-path
              (assert-error (guard/resolve workspace raw-path)
                            "путь вне рабочей папки"))))
        (testing "normalization may bring an initially external path back inside"
          (doseq [[raw-path expected]
                  [["../outside/../workspace/inside.txt" file]
                   [(str (.resolve outside "../workspace/inside.txt")) file]
                   [(str (.resolve outside "../workspace/new/nested/file.txt"))
                    (.resolve root "new/nested/file.txt")]]]
            (testing raw-path
              (is (= expected (guard/resolve workspace raw-path))))))))))

(deftest internal-symlinks-test
  (with-temp-directory
    (fn [^Path root]
      (let [directory (Files/createDirectory (.resolve root "data")
                                             (make-array FileAttribute 0))
            file (Files/createFile (.resolve directory "данные.txt")
                                   (make-array FileAttribute 0))
            workspace (guard/create-guard root)]
        (doseq [[link target] [["file-link" (.relativize root file)]
                               ["dir-link" (.getFileName directory)]
                               ["file-chain" (.getFileName (.resolve root "file-link"))]
                               ["dir-chain" (.getFileName (.resolve root "dir-link"))]
                               ["absolute-file-link" file]
                               ["absolute-dir-link" directory]]]
          (Files/createSymbolicLink (.resolve root ^String link) target
                                    (make-array FileAttribute 0)))
        (testing "results retain lexical symlink names rather than canonical targets"
          (doseq [[raw-path expected]
                  [["file-link" "file-link"]
                   ["file-chain" "file-chain"]
                   ["absolute-file-link" "absolute-file-link"]
                   ["dir-link" "dir-link"]
                   ["dir-link/данные.txt" "dir-link/данные.txt"]
                   ["dir-chain/данные.txt" "dir-chain/данные.txt"]
                   ["absolute-dir-link/данные.txt" "absolute-dir-link/данные.txt"]
                   ["./dir-link/./данные.txt" "dir-link/данные.txt"]
                   ["dir-link/new" "dir-link/new"]
                   ["dir-link/new/nested/file.txt" "dir-link/new/nested/file.txt"]
                   ["dir-chain/new/../new/nested/file.txt" "dir-chain/new/nested/file.txt"]
                   ["absolute-dir-link/new/nested/file.txt" "absolute-dir-link/new/nested/file.txt"]]]
            (doseq [input [raw-path (str (.resolve root ^String raw-path))]]
              (testing input
                (is (= (.resolve root ^String expected)
                       (guard/resolve workspace input)))))))))))

(deftest external-symlinks-test
  (with-temp-directory
    (fn [^Path directory]
      (let [root (Files/createDirectory (.resolve directory "root")
                                       (make-array FileAttribute 0))
            outside (Files/createDirectory (.resolve directory "outside")
                                          (make-array FileAttribute 0))
            file (Files/createFile (.resolve outside "secret.txt")
                                   (make-array FileAttribute 0))
            workspace (guard/create-guard root)]
        (doseq [[link target] [["outside-file" file]
                               ["outside-dir" outside]
                               ["file-chain" (.getFileName (.resolve root "outside-file"))]
                               ["dir-chain" (.getFileName (.resolve root "outside-dir"))]
                               ["relative-outside" (.relativize root outside)]]]
          (Files/createSymbolicLink (.resolve root ^String link) target
                                    (make-array FileAttribute 0)))
        (doseq [path ["outside-file"
                     "outside-dir"
                     "outside-dir/secret.txt"
                     "file-chain"
                     "dir-chain/secret.txt"
                     "relative-outside/secret.txt"
                     "outside-dir/new.txt"
                     "outside-dir/new/nested/file.txt"
                     "dir-chain/new/nested/file.txt"
                     "relative-outside/new/nested/file.txt"]
                raw-path [path (str (.resolve root ^String path))]]
          (testing raw-path
            (assert-error (guard/resolve workspace raw-path)
                          "путь ведёт наружу через симлинк")))))))

(deftest dangling-symlinks-test
  (with-temp-directory
    (fn [^Path directory]
      (let [root (Files/createDirectory (.resolve directory "root")
                                       (make-array FileAttribute 0))
            outside (Files/createDirectory (.resolve directory "outside")
                                          (make-array FileAttribute 0))
            workspace (guard/create-guard root)]
        (doseq [[link target] [["missing-inside" (.resolve root "missing")]
                               ["missing-outside" (.resolve outside "missing")]
                               ["chain-inside" (.getFileName (.resolve root "missing-inside"))]
                               ["chain-outside" (.getFileName (.resolve root "missing-outside"))]]]
          (Files/createSymbolicLink (.resolve root ^String link) target
                                    (make-array FileAttribute 0)))
        (doseq [link ["missing-inside" "missing-outside" "chain-inside" "chain-outside"]
                suffix ["" "/child.txt" "/new/nested/file.txt"]]
          (let [raw-path (str link suffix)]
            (testing raw-path
              (assert-error (guard/resolve workspace raw-path)
                            "не удалось проверить путь"))))))))

(deftest symlink-loops-test
  (with-temp-directory
    (fn [^Path root]
      (let [workspace (guard/create-guard root)]
        (doseq [[link target] [["self" "self"]
                               ["cycle-a" "cycle-b"]
                               ["cycle-b" "cycle-a"]
                               ["cycle-chain" "cycle-a"]]]
          (Files/createSymbolicLink (.resolve root ^String link)
                                    (.getFileName (.resolve root ^String target))
                                    (make-array FileAttribute 0)))
        (doseq [link ["self" "cycle-a" "cycle-b" "cycle-chain"]
                suffix ["" "/child.txt" "/new/nested/file.txt"]]
          (let [raw-path (str link suffix)]
            (testing raw-path
              (assert-error (guard/resolve workspace raw-path)
                            "не удалось проверить путь"))))))))

(deftest symlink-root-test
  (with-temp-directory
    (fn [^Path directory]
      (let [root (Files/createDirectory (.resolve directory "real-root")
                                       (make-array FileAttribute 0))
            outside (Files/createDirectory (.resolve directory "outside")
                                          (make-array FileAttribute 0))
            alias (Files/createSymbolicLink (.resolve directory "alias") root
                                            (make-array FileAttribute 0))
            file (Files/createFile (.resolve root "file.txt")
                                   (make-array FileAttribute 0))
            workspace (guard/create-guard alias)]
        (Files/createSymbolicLink (.resolve root "inside-link") file
                                  (make-array FileAttribute 0))
        (Files/createSymbolicLink (.resolve root "outside-link") outside
                                  (make-array FileAttribute 0))
        (is (= alias (:root workspace)))
        (doseq [[raw-path expected] [["." alias]
                                     ["file.txt" (.resolve alias "file.txt")]
                                     [(str (.resolve alias "file.txt")) (.resolve alias "file.txt")]
                                     ["unused/../file.txt" (.resolve alias "file.txt")]
                                     ["inside-link" (.resolve alias "inside-link")]
                                     ["new/nested/file.txt" (.resolve alias "new/nested/file.txt")]]]
          (testing raw-path
            (is (= expected (guard/resolve workspace raw-path)))))
        (doseq [raw-path ["outside-link" "outside-link/new/nested/file.txt"]]
          (testing raw-path
            (assert-error (guard/resolve workspace raw-path)
                          "путь ведёт наружу через симлинк")))))))

(deftest invalid-root-test
  (with-temp-directory
    (fn [^Path directory]
      (let [missing (.resolve directory "missing")
            file (Files/createFile (.resolve directory "file.txt")
                                   (make-array FileAttribute 0))
            dangling (Files/createSymbolicLink (.resolve directory "dangling") missing
                                               (make-array FileAttribute 0))
            loop (Files/createSymbolicLink (.resolve directory "loop")
                                           (.getFileName (.resolve directory "loop"))
                                           (make-array FileAttribute 0))]
        (doseq [[label ^Path root] [["missing root" missing]
                                    ["file as root" file]
                                    ["dangling root symlink" dangling]
                                    ["cyclic root symlink" loop]]]
          (testing label
            (let [workspace (guard/create-guard root)]
              (doseq [raw-path ["." "file.txt" "new/nested/file.txt"
                               (str (.resolve root "new/nested/file.txt"))]]
                (testing raw-path
                  (assert-error (guard/resolve workspace raw-path)
                                "не удалось проверить путь"))))))))))

(deftest root-revalidated-test
  (with-temp-directory
    (fn [^Path directory]
      (let [root (Files/createDirectory (.resolve directory "root")
                                       (make-array FileAttribute 0))
            workspace (guard/create-guard root)]
        (is (= root (guard/resolve workspace ".")))
        (Files/delete root)
        (testing "a root removed after guard creation must fail closed"
          (doseq [raw-path ["." "new/nested/file.txt"]]
            (assert-error (guard/resolve workspace raw-path)
                          "не удалось проверить путь")))
        (Files/createFile root (make-array FileAttribute 0))
        (testing "a root replaced by a file must fail closed"
          (doseq [raw-path ["." "new/nested/file.txt"]]
            (assert-error (guard/resolve workspace raw-path)
                          "не удалось проверить путь")))))))

(deftest real-path-test
  (with-temp-directory
    (fn [^Path root]
      (let [file (Files/createFile (.resolve root "file.txt")
                                   (make-array FileAttribute 0))
            nested (Files/createDirectory (.resolve root "nested")
                                          (make-array FileAttribute 0))
            link (Files/createSymbolicLink (.resolve root "link") (.getFileName file)
                                           (make-array FileAttribute 0))]
        (doseq [[^Path input ^Path target] [[root root]
                                            [file file]
                                            [link file]
                                            [(.resolve nested "../file.txt") file]]]
          (testing (str input)
            (let [result (guard/real-path input)]
              (is (instance? Path result))
              (is (= (.toRealPath target (make-array LinkOption 0)) result)))))))))

(deftest io-error-test
  (with-temp-directory
    (fn [^Path root]
      (let [file (Files/createFile (.resolve root "file.txt")
                                   (make-array FileAttribute 0))
            directory (Files/createDirectory (.resolve root "existing")
                                             (make-array FileAttribute 0))
            workspace (guard/create-guard root)
            original-real-path guard/real-path]
        (doseq [[label failing-path raw-path]
                [["root with existing target" root "file.txt"]
                 ["root with missing target" root "new/nested/file.txt"]
                 ["existing target" file "file.txt"]
                 ["nearest existing ancestor" directory "existing/new/nested/file.txt"]]]
          (testing label
            (let [failing-paths (set [failing-path (original-real-path failing-path)])]
              (with-redefs [guard/real-path
                            (fn [path]
                              (if (contains? failing-paths path)
                                (throw (IOException. "simulated I/O failure"))
                                (original-real-path path)))]
                (assert-error (guard/resolve workspace raw-path)
                              "не удалось проверить путь")))))))))

(deftest symbolic-link-io-error-test
  (let [provider (proxy [FileSystemProvider] []
                   (readAttributes [_ attribute-type options]
                     (is (= BasicFileAttributes attribute-type))
                     (is (= [LinkOption/NOFOLLOW_LINKS] (vec options)))
                     (throw (IOException. "simulated metadata I/O failure"))))
        filesystem (proxy [FileSystem] []
                     (provider [] provider))
        path (reify Path
               (getFileSystem [_] filesystem))]
    (is (thrown-with-msg? IOException #"simulated metadata I/O failure"
                          (#'guard/symbolic-link? path)))))
