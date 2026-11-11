(ns harness-clj.tools.workspace-guard
  (:refer-clojure :exclude [resolve])
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io IOException]
           [java.nio.file Files InvalidPathException LinkOption NoSuchFileException Path]))

(defn- path [arg]
  (.toPath (io/file arg)))

(defn create-guard [root]
  {:root (path root)})

(defn- error [& arg] 
  (if (sequential? arg) {:isError true :content (apply str arg)} {:isError true :content arg}))

(error "1" " " "2")
(error "1")

(defn resolve [guard raw-path]
  (if 
   (or (nil? raw-path) (.isBlank raw-path))(error "путь не указан")
    (let [root (:root guard)
          candidate (path raw-path)
           resolved (if (.isAbsolute candidate)
                      candidate
                      (-> root
                          (.resolve candidate)
                          (.normalize)))] 
      (if (.startsWith resolved root) (error "путь вне рабочей папки: " resolved " (рабочая папка: " root ")")
          (let [real-path (.toRealPath resolved)]
            ;; need to fix
            ;; NoSuchFileException
            ;; IOException

            (if (.startsWith real-path root) (error "путь ведёт наружу через симлинк: "  raw-path )
              resolved))))))
   

(resolve (create-guard ".") "deps.edn")

(.normalize (.resolve (:root (create-guard ".")) (path "deps.edn")))


