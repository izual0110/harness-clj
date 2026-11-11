(ns harness-clj.tools-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.tools :as tools]))

(def ^:private read-definition
  {:description "Read a file"
   :parameters {:type "object"
                :properties {:file-path {:type "string"}}
                :required ["file-path"]}})

(deftest specs-test
  (let [registry {"Read" (assoc read-definition
                               :name "ignored"
                               :call (fn [_] (throw (ex-info "Must not execute" {}))))}
        expected [{:type "function" :function (assoc read-definition :name "Read")}]]
    (is (= expected (tools/specs registry)))
    (is (= expected (json/read-str (json/write-str (tools/specs registry))
                                  :key-fn keyword)))
    (with-redefs [tools/tools registry]
      (is (= expected (tools/specs)))))
  (is (= [] (tools/specs {}))))

(deftest multiple-tool-specs-test
  (let [registry {"Read" (assoc read-definition :call identity)
                  "Echo" {:description "Echo arguments"
                          :parameters {:type "object"}
                          :call identity}}
        specs (tools/specs registry)]
    (is (= #{"Read" "Echo"} (set (map #(get-in % [:function :name]) specs))))
    (is (= 2 (count specs)))
    (is (every? #(= "function" (:type %)) specs))
    (is (every? #(not (contains? (:function %) :call)) specs))))

(deftest execute-tool-test
  (doseq [result ["Привет" {:path "файл.txt" :lines ["один" "два"]}
                  [1 2 3] 42 nil false]]
    (testing (pr-str result)
      (let [executions (atom [])
            arguments {:file-path "README.md" :offset 2 :limit 20}
            registry {"Read" (assoc read-definition :call
                                    (fn [args]
                                      (swap! executions conj args)
                                      result))}
            call {:function {:name "Read" :arguments arguments}}
            output (tools/execute-tool registry call)]
        (is (string? output))
        (is (= result (json/read-str output :key-fn keyword)))
        (is (= [arguments] @executions))
        (with-redefs [tools/tools registry]
          (is (= output (tools/execute-tool call))))))))

(deftest unknown-tool-test
  (let [registry {"Read" (assoc read-definition :call
                               (fn [_] (throw (ex-info "Must not execute" {}))))}
        call {:function {:name "unknown" :arguments {}}}]
    (is (= "нет инструмента 'unknown'. Доступны: [\"Read\"]"
           (tools/execute-tool registry call)))
    (is (= "нет инструмента 'unknown'. Доступны: []"
           (tools/execute-tool {} call)))))

(deftest execution-error-test
  (let [registry {"Read" (assoc read-definition :call
                               (fn [_] (throw (ex-info "File not found" {:path "missing.clj"}))))}
        call {:function {:name "Read" :arguments {:file-path "missing.clj"}}}]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"File not found"
                         (tools/execute-tool registry call)))))
