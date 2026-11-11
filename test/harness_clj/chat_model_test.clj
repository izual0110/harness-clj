(ns harness-clj.chat-model-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.chat-model :as chat-model])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient]
           [java.nio.charset StandardCharsets]))

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
      (let [config {:host (str "http://127.0.0.1:" (.getPort (.getAddress server)) "/")
                    :timeout-ms 5000
                    :defaults {:model "test-model"
                               :stream false
                               :keep_alive "5m"
                               :options {:temperature 0.8}}}
            client (chat-model/client config)]
        (try
          (f client requests)
          (finally
            (chat-model/close client))))
      (finally
        (.stop server 0)))))

(deftest config-test
  (let [config (chat-model/load-config)
        host (URI/create (:host config))]
    (is (contains? #{"http" "https"} (.getScheme host)))
    (is (some? (.getHost host)))
    (is (pos? (:timeout-ms config)))
    (is (string? (get-in config [:defaults :model])))
    (is (false? (get-in config [:defaults :stream])))
    (is (false? (get-in config [:defaults :think])))
    (is (not (contains? (get-in config [:defaults :options]) :think))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"resource not found"
                       (chat-model/load-config "missing-chat-model.edn"))))

(deftest explicit-config-test
  (with-redefs [chat-model/load-config (fn [& _]
                                        (throw (ex-info "Unexpected resource config access" {})))]
    (with-server 200 "{}"
      (fn [client _]
        (is (= {} (chat-model/generate client "Hello")))))))

(deftest close-test
  (with-server 200 "{}"
    (fn [client requests]
      (chat-model/list-models client)
      (is (nil? (chat-model/close client)))
      (is (.isTerminated ^HttpClient (:http-client client)))
      (is (nil? (chat-model/close client)))
      (is (thrown? java.io.IOException (chat-model/list-models client)))
      (is (= 1 (count @requests))))))

(deftest generate-test
  (with-server 200 "{\"response\":\"Привет!\",\"done\":true}"
    (fn [client requests]
      (is (= {:response "Привет!" :done true}
             (chat-model/generate client "Привет")))
      (is (= [{:method "POST"
               :path "/api/generate"
               :content-type "application/json"
               :raw-body (json/write-str (assoc (:defaults client) :prompt "Привет")
                                                        :escape-unicode false)
               :body (assoc (:defaults client) :prompt "Привет")}]
             @requests)))))

(deftest override-parameters-test
  (with-server 200 "{\"response\":\"ok\",\"done\":true}"
    (fn [client requests]
      (chat-model/generate client "Hello"
                           {:model "other-model" :keep_alive 0
                            :options {:seed 42} :format "json" :prompt "Ignored"})
      (let [body (:body (first @requests))]
        (is (= "Hello" (:prompt body)))
        (is (= "other-model" (:model body)))
        (is (= 0 (:keep_alive body)))
        (is (= "json" (:format body)))
        (is (= {:temperature 0.8 :seed 42} (:options body)))
        (is (false? (:stream body)))))))

(deftest chat-test
  (with-server 200 "{\"message\":{\"role\":\"assistant\",\"content\":\"Hi!\"},\"done\":true}"
    (fn [client requests]
      (let [messages [{:role "system" :content "Be brief"}
                      {:role "user" :content "Hello"}]
            tools [{:type "function" :function {:name "weather"}}]
            result (chat-model/chat client messages tools {:tools [] :options {:temperature 0}
                                                           :messages []})
            body (:body (first @requests))]
        (is (= "Hi!" (get-in result [:message :content])))
        (is (= "/api/chat" (:path (first @requests))))
        (is (= messages (:messages body)))
        (is (= tools (:tools body)))
        (is (= 0 (get-in body [:options :temperature])))
        (is (false? (:stream body)))))))

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
  (doseq [[complete args] [[chat-model/generate ["Hello"]]
                          [chat-model/chat [[{:role "user" :content "Hello"}] []]]]]
    (with-server 200 "{}"
      (fn [client requests]
        (let [client (assoc-in client [:defaults :think] false)]
          (apply complete client args)
          (apply complete client (conj args {:think true}))
          (apply complete (assoc-in client [:defaults :think] true)
                 (conj args {:think false}))
          (is (= [false true false]
                 (mapv #(get-in % [:body :think]) @requests)))
          (is (every? #(not (contains? (get-in % [:body :options]) :think))
                      @requests)))))))

(deftest list-models-test
  (with-server 200 "{\"models\":[{\"name\":\"llama3.2:latest\"}]}"
    (fn [client requests]
      (is (= {:models [{:name "llama3.2:latest"}]} (chat-model/list-models client)))
      (is (= "GET" (:method (first @requests))))
      (is (= "/api/tags" (:path (first @requests))))
      (is (nil? (:body (first @requests)))))))

(deftest list-models-ignores-generation-defaults-test
  (with-server 200 "{\"models\":[]}"
    (fn [client requests]
      (is (= {:models []}
             (chat-model/list-models (assoc-in client [:defaults :stream] true))))
      (is (nil? (:body (first @requests)))))))

(deftest errors-test
  (doseq [[status body] [[404 "{\"error\":\"model not found\"}"]
                         [502 "Bad Gateway"]]]
    (testing (str "HTTP " status)
      (with-server status body
        (fn [client _]
          (let [error (try (chat-model/generate client "Hello")
                           (catch clojure.lang.ExceptionInfo e e))]
            (is (instance? clojure.lang.ExceptionInfo error))
            (is (= status (:status (ex-data error))))
            (is (= body (:body (ex-data error)))))))))
  (with-server 200 "{\"error\":\"generation failed\"}"
    (fn [client _]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"generation failed"
                           (chat-model/generate client "Hello"))))))

(deftest non-streaming-parameters-test
  (doseq [[complete args] [[chat-model/generate ["Hello"]]
                          [chat-model/chat [[] []]]]]
    (with-server 200 "{}"
      (fn [client requests]
        (apply complete (update client :defaults dissoc :stream) args)
        (is (false? (get-in (first @requests) [:body :stream])))
        (apply complete (assoc-in client [:defaults :stream] true)
               (conj args {:stream false}))
        (is (false? (get-in (second @requests) [:body :stream])))))))

(deftest streaming-rejected-test
  (doseq [[complete args] [[chat-model/generate ["Hello"]]
                          [chat-model/chat [[] []]]]]
    (with-server 200 "{}"
      (fn [client requests]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Streaming is not supported"
                             (apply complete client (conj args {:stream true}))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Streaming is not supported"
                             (apply complete (assoc-in client [:defaults :stream] true) args)))
        (is (empty? @requests))))))
