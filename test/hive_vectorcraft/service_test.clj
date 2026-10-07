(ns hive-vectorcraft.service-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-vectorcraft.catalog :as catalog]
            [hive-vectorcraft.service :as service]
            [hive-vectorcraft.addon :as addon]
            [hive-vectorcraft.stub :as stub]
            [hive-addon.protocol :as protocol]))

(def entries {:engine [{:id "shape.rectangle" :label "Rectangle"}]
              :mcp [{:name "run_command"}]
              :control ["engine.execute"] :reference-revision "65c5953"})

(deftrifecta catalog-contract service/catalog
  {:golden-path "test/golden/service-catalog.edn"
   :cases {:overview [entries nil] :mcp [entries "mcp"] :unknown [entries "unknown"]}
   :apply? true :gen (gen/tuple (gen/return entries) (gen/elements [nil "mcp" "engine" "unknown"]))
   :pred #(or (contains? % :ok) (contains? % :error)) :num-tests 80
   :mutations [["empty-catalog" (fn [& _] {:ok []})]]})

(deftrifecta doctor-contract service/doctor
  {:golden-path "test/golden/service-doctor.edn"
   :cases {:absent [entries nil]}
   :apply? true :gen (gen/tuple (gen/return entries) (gen/return nil))
   :pred #(= :absent (get-in % [:ok :transport])) :num-tests 80
   :mutations [["pretend-present" (fn [& _] {:ok {:transport :injected}})]]})

(deftest stub-and-recording
  (let [[transport calls] (stub/recording (stub/stub {:ok true :result {:id 17}}))
        response (service/call entries transport "shape.rectangle" {:x 2} 6)]
    (is (= {:ok {:id 17}} response))
    (is (= [{:id 6 :method "engine.execute" :params {:command "shape.rectangle" :params {:x 2}}}] @calls))
    (is (= :vectorcraft/unknown-command (get-in (service/call entries transport "fake" {} 1) [:error :kind])))
    (is (= 1 (count @calls)))
    (is (= :vectorcraft/no-transport (get-in (service/call entries nil "shape.rectangle" {} 1) [:error :kind])))))

(deftest addon-mounts-without-transport
  (let [instance (addon/addon-ctor {})]
    (is (:success? (protocol/initialize! instance {})))
    (is (= :degraded (:status (protocol/health instance))))
    (let [definition (first (protocol/tools instance))
          handler (:handler definition)]
      (is (= "vectorcraft" (:name definition)))
      (is (= true (:isError (handler {"command" "call" "engine_command" "shape.rectangle"}))))
      (is (= true (:isError (handler {"command" "call" "engine_command" "shape.rectangle" "params" nil}))))
      (is (= true (:isError (handler {"command" "call" "engine_command" "shape.rectangle" "id" -1}))))
      (is (= true (:isError (handler {"command" "wrong"}))))
      (is (not (:isError (handler {"command" "doctor"})))))
    (protocol/shutdown! instance)))

(deftest reference-catalog-loaded
  (let [actual (catalog/load-catalog)]
    (is (= 519 (count (:engine actual))))
    (is (= 25 (count (:mcp actual))))
    (is (= 24 (count (:control actual))))
    (is (some #(= "ui.dialog.confirm" %) (:control actual)))
    (is (not-any? #(= ".confirm" %) (:control actual)))))
