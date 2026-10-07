(ns portability
  "Portable deterministic oracle: identical values on JVM, cljw, cljrs and cljs/Node."
  (:require [hive-vectorcraft.core :as core]))

(def catalog {:engine [{:id "shape.rectangle" :label "Rectangle" :params "{x,y}"}]
              :control ["engine.execute" "document.inspect"]})

(defn check
  "Return a value summary of the 80-case oracle; failures are indexed values."
  []
  (let [cases (for [n (range 80)]
                (let [known? (= 0 (mod n 2))
                      id (if known? "shape.rectangle" "shape.unknown")
                      result (core/request catalog id {:x n} n)
                      line (core/frame "{\"method\":\"engine.execute\"}")
                      response (core/response-line {:ok true :result n})]
                  (and (= known? (contains? result :ok))
                       (= (not known?) (contains? result :error))
                       (= (str "{\"method\":\"engine.execute\"}" "\n") (:ok line))
                       (= {:ok n} response)
                       (= :vectorcraft/unknown-method
                          (get-in (core/control-request catalog "unknown" {} n) [:error :kind])))))]
    {:passes (count (filter identity cases)) :total (count cases)}))

(defn -main [& _]
  (let [result (check)]
    (println result)
    (when (not= (:passes result) (:total result))
      (throw (ex-info "VectorCraft portability oracle failed" result)))))

#?(:cljs (set! *main-cli-fn* -main))
