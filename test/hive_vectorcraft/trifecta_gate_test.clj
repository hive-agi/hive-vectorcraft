(ns hive-vectorcraft.trifecta-gate-test
  "The test universe is read from production source, not from the tests being counted."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [hive-vectorcraft.contracts-test :as census]))

(deftest all-public-functions-have-three-facets
  (let [source (->> (file-seq (io/file "src"))
                    (filter #(and (.isFile %) (or (.endsWith (.getName %) ".clj")
                                                  (.endsWith (.getName %) ".cljc")))))
        public (set (mapcat census/public-defs source))
        tests (->> (file-seq (io/file "test"))
                   (filter #(and (.isFile %) (.endsWith (.getName %) "_test.clj"))))
        specs (for [file tests
                    :let [forms (census/source-forms file)
                          requires (->> (rest (first forms))
                                        (filter seq?)
                                        (filter #(= :require (first %)))
                                        first rest (filter vector?))
                          aliases (into {} (keep (fn [[ns-name & opts]]
                                                   (when-let [alias (second (drop-while #(not= % :as) opts))]
                                                     [(str alias) (str ns-name)])) requires))]
                    form forms
                    :when (and (seq? form) (= 'deftrifecta (first form)))]
                (let [[_ _ subject options] form
                      resolved (if-let [prefix (namespace subject)]
                                 (symbol (get aliases prefix prefix) (name subject)) subject)]
                  {:subject resolved :options options}))
        subjects (set (map :subject specs))]
    (is (>= (count public) 10) "Never count a vacuous test universe")
    (is (set/subset? public subjects) (str "missing: " (set/difference public subjects)))
    (is (every? #(and (contains? (:options %) :golden-path)
                      (contains? (:options %) :gen)
                      (seq (:mutations (:options %)))) specs)
        "Every declared trifecta must include golden, property and actual mutations")))
