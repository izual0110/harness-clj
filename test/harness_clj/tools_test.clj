(ns harness-clj.tools-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.tools :as tools]
            [harness-clj.workspace-guard :as guard])
  (:import [java.nio.file Files FileVisitOption Path]
           [java.nio.file.attribute FileAttribute]))

(def ^:private read-definition
  {:description "Read a file"
   :parameters {:type "object"
                :properties {:file-path {:type "string"}}
                :required ["file-path"]}})

(defn- with-temp-directory [f]
  (let [directory (Files/createTempDirectory "tools-test-" (make-array FileAttribute 0))]
    (try
      (f directory)
      (finally
        (with-open [paths (Files/walk directory (make-array FileVisitOption 0))]
          (doseq [^Path path (reverse (iterator-seq (.iterator paths)))]
            (Files/deleteIfExists path)))))))

(deftest create-tools-test
  (let [workspace (guard/create-guard ".")
        {:keys [tools specs exec-fn] :as tool-set} (tools/create-tools workspace)]
    (is (identical? workspace (:guard tool-set)))
    (is (= #{"Read" "Glob"} (set (keys tools))))
    (is (every? (comp fn? :call) (vals tools)))
    (is (= (#'tools/specs tools) specs))
    (is (fn? exec-fn))
    (is (= "нет инструмента 'unknown'. Доступны: [\"Read\" \"Glob\"]"
           (exec-fn {:function {:name "unknown" :arguments {}}})))))

(deftest create-tools-guard-isolation-test
  (with-temp-directory
    (fn [^Path directory]
      (let [first-root (Files/createDirectory (.resolve directory "first")
                                              (make-array FileAttribute 0))
            second-root (Files/createDirectory (.resolve directory "second")
                                               (make-array FileAttribute 0))
            first-file (.resolve first-root "shared.txt")
            second-file (.resolve second-root "shared.txt")
            first-tools (tools/create-tools (guard/create-guard first-root))
            second-tools (tools/create-tools (guard/create-guard second-root))
            arguments {:file-path "shared.txt"}]
        (spit (str first-file) "first")
        (spit (str second-file) "second")
        (doseq [[tool-set content outside-file]
                [[first-tools "first" second-file]
                 [second-tools "second" first-file]]]
          (let [expected {:isError false :content (str "     1\t" content)}]
            (is (= expected ((get-in tool-set [:tools "Read" :call]) arguments)))
            (is (= expected ((:exec-fn tool-set) {:function {:name "Read" :arguments arguments}}))))
          (let [result ((:exec-fn tool-set)
                        {:function {:name "Read" :arguments {:file-path (str outside-file)}}})]
            (is (true? (:isError result)))
            (is (str/starts-with? (:content result) "путь вне рабочей папки:"))))))))

(deftest specs-test
  (let [registry {"Read" (assoc read-definition
                               :name "ignored"
                               :call (fn [_] (throw (ex-info "Must not execute" {}))))}
        expected [{:type "function" :function (assoc read-definition :name "Read")}]]
    (is (= expected (#'tools/specs registry)))
    (is (= expected (json/read-str (json/write-str (#'tools/specs registry))
                                  :key-fn keyword))))
  (is (= [] (#'tools/specs {}))))

(deftest multiple-tool-specs-test
  (let [registry {"Read" (assoc read-definition :call identity)
                  "Echo" {:description "Echo arguments"
                          :parameters {:type "object"}
                          :call identity}}
        specs (#'tools/specs registry)]
    (is (= #{"Read" "Echo"} (set (map #(get-in % [:function :name]) specs))))
    (is (= 2 (count specs)))
    (is (every? #(= "function" (:type %)) specs))
    (is (every? #(not (contains? (:function %) :call)) specs))))

(deftest execute-tool-test
  (doseq [result ["Привет" {:path "файл.txt" :lines ["один" "два"]}
                  {:isError false :content "Привет"}
                  {:isError true :content "File not found"}
                  [1 2 3] 42 nil false]]
    (testing (pr-str result)
      (let [executions (atom [])
            arguments {:file-path "README.md" :offset 2 :limit 20}
            registry {"Read" (assoc read-definition :call
                                    (fn [args]
                                      (swap! executions conj args)
                                      result))}
            call {:function {:name "Read" :arguments arguments}}
            output (#'tools/execute-tool registry call)]
        (is (= result output))
        (is (identical? result output))
        (is (= [arguments] @executions))))))

(deftest unknown-tool-test
  (let [registry {"Read" (assoc read-definition :call
                               (fn [_] (throw (ex-info "Must not execute" {}))))}
        call {:function {:name "unknown" :arguments {}}}]
    (is (= "нет инструмента 'unknown'. Доступны: [\"Read\"]"
           (#'tools/execute-tool registry call)))
    (is (= "нет инструмента 'unknown'. Доступны: []"
           (#'tools/execute-tool {} call)))))

(deftest execution-error-test
  (let [registry {"Read" (assoc read-definition :call
                               (fn [_] (throw (ex-info "File not found" {:path "missing.clj"}))))}
        call {:function {:name "Read" :arguments {:file-path "missing.clj"}}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"File not found"
                         (#'tools/execute-tool registry call)))))
