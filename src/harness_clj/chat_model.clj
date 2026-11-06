(ns harness-clj.chat-model
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
                      [java.time Duration]))

(defn load-config
  "Reads Ollama configuration from a classpath EDN resource."
  ([] (load-config "chat-model.edn"))
  ([resource-name]
   (if-let [resource (io/resource resource-name)]
     (edn/read-string (slurp resource))
     (throw (ex-info "Ollama configuration resource not found"
                     {:resource resource-name})))))

(defn client
  "Creates a reusable client. Close :http-client when no longer needed."
  ([] (client (load-config)))
  ([config]
   (assoc config :http-client
          (-> (HttpClient/newBuilder)
              (.connectTimeout (Duration/ofMillis (:timeout-ms config)))
              (.build)))))

(defn- request! [{:keys [host timeout-ms http-client]} method path body]
  (let [url (str (str/replace host #"/+$" "") path)
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Accept" "application/json"))
        request (if (= method :get)
                  (.GET builder)
                  (-> builder
                      (.header "Content-Type" "application/json")
                      (.POST (HttpRequest$BodyPublishers/ofString
                              (json/write-str body)))))
        response (.send ^HttpClient http-client (.build request)
                        (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
        status (.statusCode response)
        raw-body (.body response)]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Ollama request failed: HTTP " status)
                      {:status status :url url :body raw-body})))
    (let [result (json/read-str raw-body :key-fn keyword)]
      (when-let [error (:error result)]
        (throw (ex-info (str "Ollama error: " error)
                        {:status status :url url :body result})))
      result)))

(defn- completion! [client path input params]
  (let [defaults (:defaults client)
        body (-> (merge defaults params input)
                 (assoc :options (merge (:options defaults) (:options params))))]
    (when (:stream body)
      (throw (ex-info "Streaming is not supported; use :stream false" {})))
    (request! client :post path (assoc body :stream false))))

(defn generate
  "POST /api/generate. Returns the full response map with keyword keys."
  ([client prompt] (generate client prompt {}))
  ([client prompt params]
   (completion! client "/api/generate" {:prompt prompt} params)))

(defn chat
  "POST /api/chat. Messages use string roles, e.g. {:role \"user\" :content \"Hi\"}."
  ([client messages] (chat client messages {}))
  ([client messages params]
   (completion! client "/api/chat" {:messages messages} params)))

(defn list-models
  "GET /api/tags. Returns the full response map, including :models."
  [client]
  (request! client :get "/api/tags" nil))
