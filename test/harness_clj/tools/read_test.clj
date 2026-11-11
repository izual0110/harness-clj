(ns harness-clj.tools.read-test
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.tools :as tools])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files FileVisitOption OpenOption Path]
           [java.nio.file.attribute FileAttribute]))

(defn- with-temp-directory [f]
  (let [directory (Files/createTempDirectory (.toPath (io/file "."))
                                             ".read-test-"
                                             (make-array FileAttribute 0))]
    (try
      (f directory)
      (finally
        (with-open [paths (Files/walk directory (make-array FileVisitOption 0))]
          (doseq [^Path path (reverse (iterator-seq (.iterator paths)))]
            (Files/deleteIfExists path)))))))

(defn- write-content [^Path directory filename content]
  (Files/write (.resolve directory filename)
               (.getBytes ^String content StandardCharsets/UTF_8)
               (make-array OpenOption 0)))

(defn- read-file [path & [options]]
  ((get-in tools/tools ["Read" :call]) (assoc options :file-path (str path))))

(deftest read-file-content-test
  (with-temp-directory
    (fn [directory]
      (let [path (write-content directory "text.txt" "Привет\n\nworld\n")]
        (is (= {:isError false :content "     1\tПривет\n     2\t\n     3\tworld"}
               (read-file path)))
        (is (= (read-file path) (read-file (.toAbsolutePath path))))
        (is (= "" (with-out-str (read-file path))))
        (is (= (read-file path)
               (json/read-str
                (tools/execute-tool {:function {:name "Read"
                                                :arguments {:file-path (str path)}}})
                :key-fn keyword)))))))

(deftest read-file-pagination-test
  (with-temp-directory
    (fn [directory]
      (let [path (write-content directory "lines.txt" "one\ntwo\nthree\nfour\n")]
        (is (= {:isError false :content "     2\ttwo\n     3\tthree"}
               (read-file path {:offset 2 :limit 2})))
        (testing "the requested range is bounded by the file length"
          (doseq [limit [20 Long/MAX_VALUE]]
            (is (= {:isError false :content "     3\tthree\n     4\tfour"}
                   (read-file path {:offset 3 :limit limit})))))
        (testing "nonpositive offset and limit become 1"
          (doseq [value [0 -10]]
            (is (= {:isError false :content "     1\tone"}
                   (read-file path {:offset value :limit value})))))
        (testing "offset beyond the end produces an error"
          (is (= {:isError true
                  :content "в файле всего 4 строк, запрошено начиная с 5"}
                 (read-file path {:offset 5}))))))))

(deftest read-file-default-limit-test
  (with-temp-directory
    (fn [directory]
      (let [path (write-content directory "long.txt" (str/join "\n" (range 1 2002)))
            result (read-file path)
            lines (str/split-lines (:content result))]
        (is (false? (:isError result)))
        (is (= 2000 (count lines)))
        (is (= "  2000\t2000" (last lines)))
        (is (= {:isError false :content "  2001\t2001"}
               (read-file path {:offset 2001})))))))

(deftest read-file-errors-test
  (with-temp-directory
    (fn [^Path directory]
      (let [missing (.resolve directory "missing.txt")
            empty-file (write-content directory "empty.txt" "")
            binary (Files/write (.resolve directory "binary.dat")
                                (byte-array [-61 40])
                                (make-array OpenOption 0))]
        (is (= {:isError true :content (str "файл не найден: " missing)}
               (read-file missing)))
        (is (= {:isError true :content (str directory " — это папка, а не файл")}
               (read-file directory)))
        (is (= {:isError false :content "файл пустой"}
               (read-file empty-file {:offset 10})))
        (is (= {:isError true :content (str "файл не текстовый: " binary)}
               (read-file binary)))
        (is (= {:isError true :content "путь не указан"}
               ((get-in tools/tools ["Read" :call]) {})))
        (let [result (read-file "../outside.txt")]
          (is (true? (:isError result)))
          (is (str/starts-with? (:content result) "путь вне рабочей папки:")))
        (let [outside (-> (io/file ".") .toPath .toAbsolutePath .normalize .getParent)
              link (Files/createSymbolicLink (.resolve directory "outside") outside
                                             (make-array FileAttribute 0))
              result (read-file (.resolve link "missing.txt"))]
          (is (true? (:isError result)))
          (is (str/starts-with? (:content result) "путь ведёт наружу через симлинк:")))))))
