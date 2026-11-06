(ns harness-clj.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [harness-clj.core :as core]))

(deftest greet-test
  (testing "default greeting"
    (is (= "Hello, world!" (core/greet))))
  (testing "personal greeting"
    (is (= "Hello, Clojure!" (core/greet "Clojure")))))

(deftest main-test
  (testing "runs without arguments"
    (is (= "Hello, world!\n" (with-out-str (core/-main)))))
  (testing "uses command-line arguments as a name"
    (is (= "Hello, Ada Lovelace!\n"
           (with-out-str (core/-main "Ada" "Lovelace"))))))
