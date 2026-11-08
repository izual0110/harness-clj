(ns harness-clj.chat-model-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [harness-clj.chat-model :as chat-model])
  (:import [ch.qos.logback.classic Level Logger]
           [ch.qos.logback.core.read ListAppender]
           [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress URI]
           [java.net.http HttpClient]
           [java.nio.charset StandardCharsets]
           [org.slf4j LoggerFactory]))

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
    (is (false? (get-in config [:defaults :stream]))))
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
               :raw-body (json/write-str (assoc (:defaults client) :prompt "Привет"))
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
            result (chat-model/chat client messages {:tools tools :options {:temperature 0}
                                                     :messages []})
            body (:body (first @requests))]
        (is (= "Hi!" (get-in result [:message :content])))
        (is (= "/api/chat" (:path (first @requests))))
        (is (= messages (:messages body)))
        (is (= tools (:tools body)))
        (is (= 0 (get-in body [:options :temperature])))
        (is (false? (:stream body)))))))

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

(deftest request-debug-log-test
  (let [^Logger logger (LoggerFactory/getLogger "harness-clj.chat-model")
        appender (doto (ListAppender.)
                   (.setContext (.getLoggerContext logger))
                   (.start))
        original-level (.getLevel logger)
        original-additive (.isAdditive logger)]
    (try
      (.setLevel logger Level/DEBUG)
      (.setAdditive logger false)
      (.addAppender logger appender)
      (with-server 200 "{}"
        (fn [client requests]
          (chat-model/generate client "Привет\n\"мир\"" {:options {:seed 42}})
          (let [event (first (.-list appender))
                message (.getFormattedMessage event)]
            (is (= Level/DEBUG (.getLevel event)))
            (is (str/starts-with? message (str "Ollama request:\nPOST " (:host client)
                                               "api/generate\n")))
            (is (str/includes? message "Accept: application/json\n"))
            (is (str/includes? message "Content-Type: application/json\n"))
            (is (str/ends-with? message (str "\n\n" (:raw-body (first @requests))))))
          (chat-model/list-models client)
          (is (= (str "Ollama request:\nGET " (:host client)
                      "api/tags\nAccept: application/json\n\n")
                 (.getFormattedMessage (second (.-list appender)))))
          (.setLevel logger Level/INFO)
          (chat-model/generate client "Not logged")
          (is (= 2 (count (.-list appender))))))
      (finally
        (.detachAppender logger appender)
        (.setLevel logger original-level)
        (.setAdditive logger original-additive)
        (.stop appender)))))

(deftest non-streaming-parameters-test
  (doseq [[complete input] [[chat-model/generate "Hello"]
                            [chat-model/chat []]]]
    (with-server 200 "{}"
      (fn [client requests]
        (complete (update client :defaults dissoc :stream) input)
        (is (false? (get-in (first @requests) [:body :stream])))
        (complete (assoc-in client [:defaults :stream] true) input {:stream false})
        (is (false? (get-in (second @requests) [:body :stream])))))))

(deftest streaming-rejected-test
  (with-server 200 "{}"
    (fn [client requests]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Streaming is not supported"
                           (chat-model/generate client "Hello" {:stream true})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Streaming is not supported"
                           (chat-model/chat (assoc-in client [:defaults :stream] true) [])))
      (is (empty? @requests)))))
