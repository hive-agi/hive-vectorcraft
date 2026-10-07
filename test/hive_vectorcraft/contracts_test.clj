(ns hive-vectorcraft.contracts-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [malli.core :as m]
            [hive-vectorcraft.contracts]
            [hive-vectorcraft.catalog]
            [hive-vectorcraft.service]
            [hive-vectorcraft.addon]
            [hive-vectorcraft.schema]))

(defn source-forms [file]
  (with-open [r (java.io.PushbackReader. (io/reader file))]
    (loop [forms []]
      (let [form (read {:eof ::eof :read-cond :allow :features #{:clj}} r)]
        (if (= ::eof form) forms (recur (conj forms form)))))))

(defn public-defs [file]
  (let [forms (source-forms file)
        ns-name (second (first forms))]
    (set (for [form forms
               :when (and (seq? form) (= 'defn (first form)))]
           (symbol (str ns-name) (str (second form)))))))

(defn declared-contracts [file]
  (set (for [form (source-forms file)
             :when (and (seq? form) (= 'm/=> (first form)))]
         (second form))))

(deftest all-public-source-functions-have-contracts
  (let [files (->> (file-seq (io/file "src"))
                   (filter #(and (.isFile %) (or (.endsWith (.getName %) ".clj")
                                                 (.endsWith (.getName %) ".cljc")))))
        public (set (mapcat public-defs files))
        actual (set (for [file files
                          :let [ns-name (second (first (source-forms file)))]
                          sym (declared-contracts file)]
                      (if (namespace sym) sym (symbol (str ns-name) (name sym)))))]
    (is (>= (count public) 10) "A source-tree census must not pass vacuously")
    (is (= public actual) (str "missing " (set/difference public actual)
                               "; stale " (set/difference actual public)))
    (is (every? #(get-in (m/function-schemas) [(symbol (namespace %)) (symbol (name %))]) actual)
        (str "Malli registrations: " (keys (m/function-schemas))))))
