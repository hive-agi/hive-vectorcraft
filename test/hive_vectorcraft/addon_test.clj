(ns hive-vectorcraft.addon-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-vectorcraft.addon :as addon]
            [hive-vectorcraft.catalog :as catalog]
            [hive-vectorcraft.stub :as stub]
            [hive-addon.protocol :as protocol]))

(def entries {:engine [{:id "shape.rectangle" :label "Rectangle"}]
              :mcp [{:name "run_command"}]
              :control ["engine.execute"] :reference-revision "65c5953"})

(defn call-handler [request]
  (let [[transport calls] (stub/recording (stub/stub {:ok true :result {:id 7}}))
        handler (:handler (addon/tool entries transport))
        result (handler request)]
    {:result result :requests @calls}))

(deftrifecta tool-contract addon/tool
  {:golden-path "test/golden/addon-tool.edn"
   :cases {:tool [entries nil]}
   :apply? true :xf #(select-keys % [:name :inputSchema])
   :gen (gen/tuple (gen/return entries) (gen/return nil))
   :pred #(and (= "vectorcraft" (:name %)) (fn? (:handler %))) :num-tests 80
   :mutations [["drop-handler" (fn [_ _] {:name "vectorcraft" :inputSchema {}})]]})

(deftrifecta addon-ctor-contract addon/addon-ctor
  {:golden-path "test/golden/addon-ctor.edn"
   :cases {:default [{}] :configured [{:transport :stub}]}
   :apply? true :xf #(select-keys % [:configuration])
   :gen (gen/tuple (gen/return {}))
   :pred #(= "hive.vectorcraft" (protocol/addon-id %)) :num-tests 80
   :mutations [["drop-config" (fn [_] (addon/->VectorcraftAddon (atom {}) {:lost true}))]]})

(deftrifecta handler-contract call-handler
  {:golden-path "test/golden/addon-handler.edn"
   :cases {:valid {"command" "call" "engine_command" "shape.rectangle" "params" {:x 2} "id" 9}
           :bad-params {"command" "call" "engine_command" "shape.rectangle" "params" nil}
           :bad-id {"command" "call" "engine_command" "shape.rectangle" "id" -1}
           :unknown {"command" "wrong"}
           :doctor {"command" "doctor"}}
   :gen (gen/elements [{"command" "doctor"}
                       {"command" "call" "engine_command" "shape.rectangle"}
                       {"command" "call" "engine_command" "bad"}])
   :pred #(and (vector? (get-in % [:result :content])) (vector? (:requests %)))
   :num-tests 80
   :mutations [["skip-validation" (fn [_] {:result {:content [{:type "text" :text "ok"}]}
                                         :requests [{:method "engine.execute"}]})]]})
