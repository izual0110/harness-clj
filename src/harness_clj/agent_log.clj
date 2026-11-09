(ns harness-clj.agent-log
  (:require [clojure.string :as str])
  (:import [java.net.http HttpRequest HttpResponse]
           [org.apache.logging.log4j LogManager Logger Marker MarkerManager]))

(def ^:private ^Logger logger (LogManager/getLogger "harness.agent"))
(def ^:private ^Marker request-marker (MarkerManager/getMarker "REQUEST"))
(def ^:private ^Marker response-marker (MarkerManager/getMarker "RESPONSE"))
(def ^:private ^Marker debug-marker (MarkerManager/getMarker "DEBUG"))

(defn- block [title content]
  (str "┌─ " title "\n│ "
       (str/replace (str content) #"\r\n|\r|\n" "\n│ ")
       "\n└" (apply str (repeat (max 0 80) "─"))))

(defn request [messages]
  (.info logger request-marker
         (block (str "context · messages: " (count messages))
                (str/join "\n"
                          (map (fn [{:keys [role content]}]
                                 (str "[" (if (= role "system") "system prompt" role)
                                      "] " content))
                               messages))))
  messages)

(defn response [response]
  (let [{:keys [role content] :or {role "assistant"}} (:message response)]
    (.info logger response-marker
           (block (str "[" role "] response") content)))
  response)
  
  

(defn debug [message & args]
  (when (.isDebugEnabled logger debug-marker)
    (.debug logger debug-marker
            (block "debug" (if (seq args)
                             (apply format message args)
                             message)))))

(defn debug-ollama-request [^HttpRequest request request-body]
  (when (.isDebugEnabled logger debug-marker)
    (.debug logger debug-marker 
            (block "debug · ollama request" (str (.method request) " " (.uri request) "\n"
                (str/join "\n"
                          (for [[header values] (.map (.headers request)) value values] (str header ": " value))) "\n\n"
                (or request-body "")))))
  request)

(defn debug-ollama-response [^HttpResponse response]
  (when (.isDebugEnabled logger debug-marker)
    (.debug logger debug-marker 
           (block "debug · ollama response" (str (str/join "\n"
                          (for [[header values] (.map (.headers response)) value values] (str header ": " value))) "\n\n"
                (or (.body response) "")))))
  response)
