(ns build
  (:require [clojure.java.io :as io]))

(defn -main [& _args]
  (let [compile-dir "target/classes"]
    (.mkdirs (io/file compile-dir))
    (binding [*compile-path* compile-dir]
      (compile 'harness-clj.core))
    (println "Compiled harness-clj.core to" compile-dir)))
