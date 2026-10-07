(ns hive-vectorcraft.addon
  "Single host-neutral IAddon. Catalog and diagnosis work without VectorCraft; calls require an injected port."
  (:require [hive-addon.protocol :as addon]
            [hive-vectorcraft.catalog :as catalog]
            [hive-vectorcraft.service :as service]))

(defn tool
  "Construct the one consolidated tool descriptor; clients discover commands through catalog."
  [entries transport]
  {:name "vectorcraft"
   :description "VectorCraft bridge. catalog lists engine commands/MCP tools/control methods; doctor diagnoses capabilities; call validates an engine command against the extracted reference catalog and requires an injected transport."
   :inputSchema {:type "object" :required ["command"] :additionalProperties false
                 :properties {"command" {:type "string" :enum ["catalog" "doctor" "call"]}
                              "query" {:type "string" :description "Catalog section or exact engine command id."}
                              "engine_command" {:type "string" :description "Exact engine command id to execute."}
                              "params" {:type "object" :description "Engine parameters."}
                              "id" {:description "Nonnegative integer or string request id."}}}
   :handler (fn [args]
              (let [command (or (get args "command") (:command args))
                    outcome (case command
                              "catalog" (service/catalog entries (or (get args "query") (:query args)))
                              "doctor" (service/doctor entries transport)
                              "call" (service/call entries transport
                                                   (or (get args "engine_command") (:engine_command args))
                                                   (cond (contains? args "params") (get args "params")
                                                         (contains? args :params) (:params args)
                                                         :else {})
                                                   (cond (contains? args "id") (get args "id")
                                                         (contains? args :id) (:id args)
                                                         :else 0))
                              {:error {:kind :vectorcraft/unknown-tool-command
                                       :hint "Choose catalog, doctor or call."}})]
                (if (:error outcome)
                  {:isError true :content [{:type "text" :text (str (get-in outcome [:error :kind]) ": " (get-in outcome [:error :hint]))}]}
                  {:content [{:type "text" :text (pr-str (:ok outcome))}]})))})

(defrecord VectorcraftAddon [state configuration]
  addon/IAddon
  (addon-id [_] "hive.vectorcraft")
  (addon-type [_] :external)
  (capabilities [_] #{:tools :health-reporting})
  (initialize! [_ cfg]
    (reset! state {:catalog (catalog/load-catalog)
                   :transport (or (:transport cfg) (:transport configuration))})
    (if (:error (:catalog @state))
      {:success? false :errors [(get-in @state [:catalog :error :hint])]}
      {:success? true :errors []}))
  (shutdown! [_] (reset! state {}) nil)
  (tools [_] (if-let [entries (:catalog @state)] [(tool entries (:transport @state))] []))
  (schema-extensions [_] {})
  (excluded-tools [_] #{})
  (hooks [_] {})
  (health [_] {:status (if (:transport @state) :ok :degraded)
               :details (if (:transport @state) {:transport :injected}
                            {:transport :absent :hint "Inject a ControlTransport; live transport is planned for wave 2."})}))

(defn addon-ctor
  "Host manifest entry point. Config may inject :transport, but never requires one to mount."
  [config]
  (->VectorcraftAddon (atom {}) (or config {})))
