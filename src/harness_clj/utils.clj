(ns harness-clj.utils)

(defn error [& parts]
  {:isError true :content (apply str parts)})

(defn ok [& parts]
  {:isError false :content (apply str parts)})