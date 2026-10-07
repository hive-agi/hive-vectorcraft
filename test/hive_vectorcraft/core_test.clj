(ns hive-vectorcraft.core-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-vectorcraft.core :as core]))

(def sample {:engine [{:id "shape.rectangle" :label "Rectangle" :params "{x,y}"}]
             :control ["document.inspect" "engine.execute"]})

(deftrifecta lookup-contract core/lookup
  {:golden-path "test/golden/lookup.edn"
   :cases {:known [sample "shape.rectangle"] :unknown [sample "bad"]}
   :apply? true :gen (gen/tuple (gen/return sample) gen/string-alphanumeric)
   :pred #(or (contains? % :ok) (contains? % :error)) :num-tests 80
   :mutations [["always-missing" (fn [_ _] {:error {:kind :vectorcraft/unknown-command}})]]})

(deftrifecta request-contract core/request
  {:golden-path "test/golden/request.edn"
   :cases {:valid [sample "shape.rectangle" {} 1] :invalid [sample "bad" {} 1]}
   :apply? true
   :gen (gen/tuple (gen/return sample) gen/string-alphanumeric (gen/return {}) gen/nat)
   :pred #(or (contains? % :ok) (contains? % :error)) :num-tests 80
   :mutations [["always-error" (fn [& _] {:error {:kind :invalid}})]]})

(deftrifecta frame-contract core/frame
  {:golden-path "test/golden/frame.edn"
   :cases {:valid "{\"method\":\"engine.commands\"}" :invalid "bad"}
   :gen gen/string-alphanumeric :pred #(or (contains? % :ok) (contains? % :error))
   :num-tests 80 :mutations [["always-valid" (fn [s] {:ok (str s "\n")})]]})

(deftest control-and-response
  (is (= "document.inspect" (get-in (core/control-request sample "document.inspect" {} 1) [:ok :method])))
  (is (= :vectorcraft/unknown-method (get-in (core/control-request sample "bogus" {} 1) [:error :kind])))
  (is (= {:ok 3} (core/response-line {"ok" true "result" 3})))
  (is (= {:ok false} (core/response-line {"ok" true "result" false})))
  (is (= {:ok nil} (core/response-line {:ok true :result nil})))
  (is (= :vectorcraft/invalid-response (get-in (core/response-line {:ok "true"}) [:error :kind])))
  (is (= :vectorcraft/upstream-error (get-in (core/response-line {:ok false :error "no doc"}) [:error :kind]))))
