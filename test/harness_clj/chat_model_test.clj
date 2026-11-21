(ns harness-clj.chat-model-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.chat-model :as chat-model])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.net.http HttpClient]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(defn- with-server [status response f]
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/api/"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (with-open [exchange exchange]
                          (let [body (slurp (.getRequestBody exchange) :encoding "UTF-8")
                                bytes (.getBytes ^String response StandardCharsets/UTF_8)]
                            (swap! requests conj
                                   {:method (.getRequestMethod exchange)
                                    :path (.getPath (.getRequestURI exchange))
                                    :raw-body body
                                    :content-type (.getFirst (.getRequestHeaders exchange)
                                                             "Content-Type")
                                    :body (when (seq body)
                                            (json/read-str body :key-fn keyword))})
                            (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
                            (.sendResponseHeaders exchange status (alength bytes))
                            (with-open [out (.getResponseBody exchange)]
                              (.write out bytes)))))))
    (.start server)
    (try
      (let [config {:ollama {:host (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/")
                             :timeout-ms 5000
                             :model "test-model"
                             :stream false
                             :think false
                             :keep_alive "5m"
                             :options {:temperature 0.8}}}
            client (chat-model/create-client config)]
        (try
          (f client requests)
          (finally
            (chat-model/close client))))
      (finally
        (.stop server 0)))))

(deftest create-client-test
  (let [config {:base-dir "."
                :ollama {:host "http://127.0.0.1:11434"
                         :timeout-ms 1234
                         :model "test-model"
                         :stream false}}
        client (chat-model/create-client config)]
    (try
      (is (= config (:config client)))
      (is (instance? HttpClient (:internal-http-client client)))
      (is (= (Duration/ofMillis 1234)
             (.orElse (.connectTimeout ^HttpClient (:internal-http-client client)) nil)))
      (finally
        (chat-model/close client)))))

(deftest close-test
  (with-server 200 "{}"
    (fn [client requests]
      (chat-model/list-models client)
      (is (nil? (chat-model/close client)))
      (is (.isTerminated ^HttpClient (:internal-http-client client)))
      (is (nil? (chat-model/close client)))
      (is (thrown? java.io.IOException (chat-model/list-models client)))
      (is (= 1 (count @requests))))))

(deftest chat-test
  (with-server 200 "{\"message\":{\"role\":\"assistant\",\"content\":\"Привет!\"},\"done\":true}"
    (fn [client requests]
      (let [messages [{:role "system" :content "Be brief"}
                      {:role "user" :content "Привет 👋"}]
            tools [{:type "function" :function {:name "weather"}}]
            body (assoc (get-in client [:config :ollama]) :messages messages :tools tools)]
        (is (= {:message {:role "assistant" :content "Привет!"} :done true}
               (chat-model/chat client messages tools)))
        (is (= [{:method "POST"
                 :path "/api/chat"
                 :content-type "application/json"
                 :raw-body (json/write-str body :escape-unicode false)
                 :body body}]
               @requests))))))

(deftest chat-parameters-test
  (with-server 200 "{}"
    (fn [client requests]
      (let [client (update-in client [:config :ollama] merge
                              {:model "other-model" :keep_alive 0 :format "json"
                               :options {:temperature 0 :seed 42}
                               :messages [] :tools []})
            messages [{:role "user" :content "Hello"}]
            tools [{:type "function" :function {:name "weather"}}]]
        (chat-model/chat client messages tools)
        (let [body (:body (first @requests))]
          (is (= messages (:messages body)))
          (is (= tools (:tools body)))
          (is (= "other-model" (:model body)))
          (is (= 0 (:keep_alive body)))
          (is (= "json" (:format body)))
          (is (= {:temperature 0 :seed 42} (:options body)))
          (is (false? (:stream body))))))))

(deftest chat-tool-calls-test
  (let [call {:function {:name "read" :arguments {:path "README.md" :limit 20}}}
        assistant {:role "assistant" :content "" :tool_calls [call]}
        response {:message assistant :done true}
        tools [{:type "function"
                :function {:name "read" :description "Read a file"
                           :parameters {:type "object"
                                        :properties {:path {:type "string"}
                                                     :limit {:type "integer"}}
                                        :required ["path"]}}}]
        messages [{:role "user" :content "Read README.md"}]
        tool-message {:role "tool" :tool_name "read" :content "1\t# Привет"}]
    (with-server 200 (json/write-str response :escape-unicode false)
      (fn [client requests]
        (let [result (chat-model/chat client messages tools)
              continuation (conj messages (:message result) tool-message)]
          (is (= response result))
          (is (= tools (get-in @requests [0 :body :tools])))
          (chat-model/chat client continuation tools)
          (is (= continuation (get-in @requests [1 :body :messages])))
          (is (= tools (get-in @requests [1 :body :tools]))))))))

(deftest thinking-parameters-test
  (with-server 200 "{}"
    (fn [client requests]
      (doseq [think [false true]]
        (chat-model/chat (assoc-in client [:config :ollama :think] think)
                         [{:role "user" :content "Hello"}] []))
      (is (= [false true] (mapv #(get-in % [:body :think]) @requests)))
      (is (every? #(not (contains? (get-in % [:body :options]) :think))
                  @requests)))))

(deftest list-models-test
  (with-server 200 "{\"models\":[{\"name\":\"llama3.2:latest\"}]}"
    (fn [client requests]
      (is (= {:models [{:name "llama3.2:latest"}]} (chat-model/list-models client)))
      (is (= "GET" (:method (first @requests))))
      (is (= "/api/tags" (:path (first @requests))))
      (is (nil? (:body (first @requests)))))))

(deftest list-models-ignores-chat-parameters-test
  (with-server 200 "{\"models\":[]}"
    (fn [client requests]
      (is (= {:models []}
             (chat-model/list-models (assoc-in client [:config :ollama :stream] true))))
      (is (nil? (:body (first @requests)))))))

(deftest errors-test
  (doseq [[status body] [[404 "{\"error\":\"model not found\"}"]
                         [502 "Bad Gateway"]]]
    (testing (str "HTTP " status)
      (with-server status body
        (fn [client _]
          (let [error (try (chat-model/chat client [{:role "user" :content "Hello"}] [])
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (instance? clojure.lang.ExceptionInfo error))
            (is (= status (:status (ex-data error))))
            (is (= body (:body (ex-data error)))))))))
  (with-server 200 "{\"error\":\"generation failed\"}"
    (fn [client _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"generation failed"
                           (chat-model/chat client [{:role "user" :content "Hello"}] []))))))
