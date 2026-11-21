(ns harness-clj.tools.file-utils 
  (:import
    [java.nio.file
     FileVisitOption
     Files
     LinkOption
     Path]))

(defn file-exists [path] (Files/exists path (make-array LinkOption 0)))
(defn directory? [path] (Files/isDirectory path (make-array LinkOption 0)))
(defn ignored? [^Path root ^Path path ignored-path-names]
  (some #(contains? ignored-path-names (str %)) (.relativize root path)))
(defn modified-at [^Path path]
  (.toMillis (Files/getLastModifiedTime path (make-array LinkOption 0))))
(defn files-walk[^Path root] (Files/walk root (make-array FileVisitOption 0)))
(defn is-regular-file [file] (Files/isRegularFile file (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])))