(ns harness-clj.chat-model
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [harness-clj.agent-log :as agent-log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(defn create-client
  "Creates a reusable client. Call close when no longer needed."
  [config]
  {:config config
   :internal-http-client (-> (HttpClient/newBuilder)
               (.connectTimeout (Duration/ofMillis (get-in config [:ollama :timeout-ms])))
               (.build))})

(defn close
  "Closes the underlying HTTP client, waiting for ongoing requests to finish."
  [{:keys [internal-http-client]}]
  (.close ^HttpClient internal-http-client))

(defn- request! [{:keys [config internal-http-client]} method path body]
  (let [host (get-in config [:ollama :host])
        timeout-ms (get-in config [:ollama :timeout-ms])
        url (str (str/replace host #"/+$" "") path)
        request-body (when body (json/write-str body :escape-unicode false))
        builder (-> (HttpRequest/newBuilder (URI/create url))
                    (.timeout (Duration/ofMillis timeout-ms))
                    (.header "Accept" "application/json"))
        request (.build (if (= method :get)
                          (.GET builder)
                          (-> builder
                              (.header "Content-Type" "application/json")
                              (.POST (HttpRequest$BodyPublishers/ofString request-body)))))
        response (.send ^HttpClient internal-http-client (agent-log/debug-ollama-request request request-body)
                        (HttpResponse$BodyHandlers/ofString StandardCharsets/UTF_8))
        status (.statusCode (agent-log/debug-ollama-response response))
        raw-body (.body response)]
    (when-not (<= 200 status 299)
      (throw (ex-info (str "Ollama request failed: HTTP " status)
                      {:status status :url url :body raw-body})))
    (let [result (json/read-str raw-body :key-fn keyword)]
      (when-let [error (:error result)]
        (throw (ex-info (str "Ollama error: " error)
                        {:status status :url url :body result})))
      result)))



(defn chat
  "POST /api/chat. Messages use string roles, e.g. {:role \"user\" :content \"Hi\"}."
  ([client messages tools]
   (request! client :post "/api/chat"
             (assoc (get-in client [:config :ollama]) :tools tools :messages messages))))

(defn list-models
  "GET /api/tags. Returns the full response map, including :models."
  [client]
  (request! client :get "/api/tags" nil))
