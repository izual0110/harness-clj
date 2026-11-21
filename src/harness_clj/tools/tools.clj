(ns harness-clj.tools.tools
  (:require [harness-clj.tools.glob :as glob]
            [harness-clj.tools.read :as read]
            [harness-clj.utils :refer [error]]))

(defn- specs [tools]
  (mapv (fn [[name tool]]
          {:type "function"
           :function (assoc (dissoc tool :call) :name name)})
        tools))

(defn- execute-tool
  [tools call]
  (let [{:keys [name arguments]} (:function call)]
    (if-let [f (get-in tools [name :call])]
      (f arguments)
      (error "нет инструмента '" name "'. Доступны: " (mapv first tools)))))

(defn create-tools [guard]
  (let [tools {"Read" (read/create-read-tool guard)
               "Glob" (glob/create-glob-tool guard)
               }]
                {:guard guard
                 :tools tools
                 :specs (specs tools)
                 :exec-fn (partial execute-tool tools)}))
