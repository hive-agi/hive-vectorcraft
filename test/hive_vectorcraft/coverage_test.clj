(ns hive-vectorcraft.coverage-test
  (:require [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-vectorcraft.core :as core]
            [hive-vectorcraft.schema :as schema]
            [hive-vectorcraft.catalog :as catalog]
            [hive-vectorcraft.service :as service]
            [hive-vectorcraft.addon :as addon]
            [hive-vectorcraft.stub :as stub]
            [hive-addon.protocol :as protocol]))

(def entries {:engine [{:id "shape.rectangle" :label "Rectangle" :params "{x,y}"}]
              :mcp [{:name "run_command"}]
              :control ["engine.execute" "document.inspect"]
              :reference-revision "65c5953"})

(deftrifecta refusal-contract core/refusal
  {:golden-path "test/golden/refusal.edn"
   :cases {:missing [:vectorcraft/no-transport "Inject a port"]
           :invalid [:vectorcraft/invalid-id "Use a valid id"]}
   :apply? true :gen (gen/tuple gen/keyword gen/string-alphanumeric)
   :pred #(and (keyword? (get-in % [:error :kind])) (string? (get-in % [:error :hint])))
   :num-tests 80
   :mutations [["drop-hint" (fn [kind _hint] {:error {:kind kind}})]]})

(deftrifecta control-request-contract core/control-request
  {:golden-path "test/golden/control-request.edn"
   :cases {:valid [entries "document.inspect" {} 9]
           :unknown [entries "bad" {} 9]
           :invalid [entries "engine.execute" [] 9]}
   :apply? true
   :gen (gen/tuple (gen/return entries) (gen/elements ["document.inspect" "bad"])
                   (gen/return {}) gen/nat)
   :pred #(or (contains? % :ok) (contains? % :error)) :num-tests 80
   :mutations [["accept-unknown" (fn [_catalog method params id]
                                    {:ok {:id id :method method :params params}})]]})

(deftrifecta response-line-contract core/response-line
  {:golden-path "test/golden/response-line.edn"
   :cases {:true {:ok true :result {:id 3}}
           :false {"ok" true "result" false}
           :null {:ok true :result nil}
           :upstream {:ok false :error "failed"}
           :invalid {:ok "true"}}
   :gen (gen/one-of [(gen/return {:ok true :result nil})
                        (gen/return {:ok false :error "failed"})
                        (gen/return {:ok "wrong"})])
   :pred #(or (contains? % :ok) (contains? % :error)) :num-tests 80
   :mutations [["coerce-null-to-false" (fn [response]
                                        (if (= true (:ok response)) {:ok false}
                                            {:error {:kind :vectorcraft/invalid-response :hint "Wrong response"}}))]]})

(deftrifecta catalog-load-contract catalog/load-catalog
  {:golden-path "test/golden/load-catalog.edn"
   :cases {:pinned []}
   :apply? true :xf (fn [result] (if (:error result) result
                                    (select-keys (update result :engine count)
                                                 [:reference-revision :engine])))
   :gen (gen/return []) :pred #(= 519 (count (:engine %))) :num-tests 20
   :mutations [["empty-reference" (fn [] {:engine [] :reference-revision "unknown"})]]})

(deftrifecta service-call-contract service/call
  {:golden-path "test/golden/service-call.edn"
   :cases {:valid [entries (stub/stub {:ok true :result {:id 3}}) "shape.rectangle" {} 4]
           :missing [entries nil "shape.rectangle" {} 4]
           :unknown [entries nil "bad" {} 4]}
   :apply? true
   :gen (gen/tuple (gen/return entries) (gen/return nil)
                   (gen/elements ["shape.rectangle" "bad"]) (gen/return {}) gen/nat)
   :pred #(or (contains? % :ok) (contains? % :error)) :num-tests 80
   :mutations [["always-unavailable" (fn [& _] {:error {:kind :vectorcraft/no-transport
                                                         :hint "Inject a port"}})]]})

(deftrifecta envelope-contract schema/envelope
  {:golden-path "test/golden/envelope.edn"
   :cases {:request :map :text :string}
   :gen (gen/elements [:string :int :boolean :map])
   :pred #(and (= :or (first %)) (= 3 (count %))) :num-tests 80
   :mutations [["drop-error" (fn [value] [:or [:map [:ok value]]])]]})
