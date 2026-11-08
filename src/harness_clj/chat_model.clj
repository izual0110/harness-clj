(ns harness-clj.chat-model
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
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
  "Creates a reusable client. Call close when no longer needed."
  ([] (client (load-config)))
  ([config]
   (assoc config :http-client
          (-> (HttpClient/newBuilder)
              (.connectTimeout (Duration/ofMillis (:timeout-ms config)))
              (.build)))))

(defn close
  "Closes the underlying HTTP client, waiting for ongoing requests to finish."
  [{:keys [http-client]}]
  (.close ^HttpClient http-client))

(defn- request! [{:keys [host timeout-ms http-client]} method path body]
  (let [url (str (str/replace host #"/+$" "") path)
        request-body (when body (json/write-str body :escape-unicode false))
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Accept" "application/json"))
        request (.build (if (= method :get)
                          (.GET builder)
                          (-> builder
                              (.header "Content-Type" "application/json")
                              (.POST (HttpRequest$BodyPublishers/ofString request-body)))))
        _ (log/debugf "Ollama request:\n%s %s\n%s\n\n%s"
                      (.method request) (.uri request)
                      (str/join "\n" (for [[header values] (.map (.headers request))
                                           value values]
                                       (str header ": " value)))
                      (or request-body ""))
        response (.send ^HttpClient http-client request
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

(defn- generation-params [{:keys [defaults]} params]
  (let [params (-> (merge defaults params)
                   (assoc :options (merge (:options defaults) (:options params))))]
    (when (:stream params)
      (throw (ex-info "Streaming is not supported; use :stream false" {})))
    (assoc params :stream false)))

(defn generate
  "POST /api/generate. Returns the full response map with keyword keys."
  ([client prompt] (generate client prompt {}))
  ([client prompt params]
   (request! client :post "/api/generate"
             (assoc (generation-params client params) :prompt prompt))))

(defn chat
  "POST /api/chat. Messages use string roles, e.g. {:role \"user\" :content \"Hi\"}."
  ([client messages] (chat client messages {}))
  ([client messages params]
   (request! client :post "/api/chat"
             (assoc (generation-params client params) :messages messages))))

(defn list-models
  "GET /api/tags. Returns the full response map, including :models."
  [client]
  (request! client :get "/api/tags" nil))
