(ns harness-clj.workspace-guard
  (:refer-clojure :exclude [resolve])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [harness-clj.utils :refer [error]])
  (:import [java.io IOException]
           [java.nio.file Files InvalidPathException LinkOption NoSuchFileException Path]
           [java.nio.file.attribute BasicFileAttributes]))

(defn- path ^Path [arg]
  (if (instance? Path arg)
    arg
    (.toPath (io/file arg))))

(defn create-guard
  "Create a workspace guard with an absolute, normalized root."
  [root]
  {:root (-> (path root) .toAbsolutePath .normalize)})

(defn real-path ^Path [^Path path]
  (.toRealPath path (make-array LinkOption 0)))

(defn- symbolic-link? [^Path path]
  (try
    (let [^BasicFileAttributes attributes
          (Files/readAttributes path BasicFileAttributes
                                (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))]
      (.isSymbolicLink attributes))
    (catch NoSuchFileException _ false)))

(defn- existing-real-path ^Path [^Path path]
  ;; Missing targets still need ancestor checks; dangling symlinks must fail closed.
  (loop [candidate path]
    (let [parent (.getParent candidate)
          resolved (try
                     (real-path candidate)
                     (catch NoSuchFileException e
                       (when (or (nil? parent) (symbolic-link? candidate))
                         (throw e))))]
      (or resolved (recur parent)))))


;;(resolve (create-guard ".") "pom.xml")


(defn resolve
  "Return a normalized absolute Path, or an {:isError true :content ...} map.
  Missing descendants are allowed. Filesystem changes after validation are not prevented."
  [guard raw-path]
  (if-not (and (string? raw-path) (not (str/blank? raw-path)))
    (error "путь не указан")
    (try
      (let [^Path root (:root guard)
            resolved (.normalize (.resolve root (path raw-path)))]
        (if-not (.startsWith resolved root)
          (error "путь вне рабочей папки: " resolved " (рабочая папка: " root ")")
          (let [real-root (real-path root)]
            (cond
              (not (Files/isDirectory real-root (make-array LinkOption 0)))
              (error "не удалось проверить путь " raw-path
                     ": рабочая папка не является директорией: " root)

              (not (.startsWith (existing-real-path resolved) real-root))
              (error "путь ведёт наружу через симлинк: " raw-path)

              :else resolved))))
      (catch InvalidPathException e
        (error "некорректный путь: " raw-path ": " (.getMessage e)))
      (catch IOException e
        (error "не удалось проверить путь " raw-path ": " (.getMessage e))))))
   