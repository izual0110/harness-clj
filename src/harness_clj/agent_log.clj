(ns harness-clj.agent-log
  (:require [clojure.string :as str])
  (:import [java.net.http HttpRequest HttpResponse]
           [org.apache.logging.log4j LogManager Logger Marker MarkerManager]))

(def ^:private ^Logger logger (LogManager/getLogger "harness.agent"))
(def ^:private ^Marker request-marker (MarkerManager/getMarker "REQUEST"))
(def ^:private ^Marker response-marker (MarkerManager/getMarker "RESPONSE"))
(def ^:private ^Marker debug-marker (MarkerManager/getMarker "DEBUG"))
(def ^:private ^Marker error-marker (MarkerManager/getMarker "ERROR"))
(def ^:private ^Marker tool-marker (MarkerManager/getMarker "TOOL"))
(def ^:private ^Integer max-content-lines 10)

(defn- block [title content]
  (str "┌─ " title "\n│ "
       (str/replace (str content) #"\r\n|\r|\n" "\n│ ")
       "\n└" (apply str (repeat (max 0 80) "─"))))

(defn- message-content [{:keys [content tool_calls]}]
  (let [text (str content
                  (when (seq tool_calls)
                    (str (when (seq content) "\n") "Tool calls: " (pr-str tool_calls))))
        lines (str/split text #"\r\n|\r|\n" (inc max-content-lines))]
    (str (str/join "\n" (take max-content-lines lines))
         (when (> (count lines) max-content-lines) "\n..."))))

(defn request [messages]
  (.info logger request-marker
         (block (str "context · messages: " (count messages))
                (str/join "\n"
                          (map (fn [{:keys [role tool_name] :as message}]
                                 (str "[" role "]" (if (nil? tool_name) " " (str "[" tool_name "]\n")) (message-content message)))
                               messages))))
  messages)

(defn response [response]
  (let [{:keys [role] :or {role "assistant"} :as message} (:message response)]
    (.info logger response-marker
           (block (str "[" role "] response") (message-content message))))
  response)


(defn turn-limit-reached [max-turns]
  (.info logger error-marker
         (block "Error" (str "Max turns [" max-turns "] limit reached!"))))

(defn tool [tool {:keys [isError] :as result}]
  (.info logger tool-marker
         (block (str "[tool][" (get-in tool [:function :name]) "] " (get-in tool [:function :arguments]) (when isError " error"))
                (message-content result))))



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
