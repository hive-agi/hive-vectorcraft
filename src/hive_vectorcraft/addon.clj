(ns hive-vectorcraft.addon
  "Single host-neutral IAddon. Catalog and diagnosis work without VectorCraft; calls require an injected port."
  (:require [hive-addon.protocol :as addon]
            [hive-vectorcraft.catalog :as catalog]
            [hive-vectorcraft.service :as service]
            [hive-vectorcraft.transport.socket :as socket]))

(defn resolve-transport
  "Injected :transport wins; else a loopback socket client when :control-port (or VECTORCRAFT_CONTROL_PORT) names the app's --control port; else nil."
  [cfg configuration]
  (or (:transport cfg)
      (:transport configuration)
      (when-let [port (or (:control-port cfg) (:control-port configuration)
                          (some-> (System/getenv "VECTORCRAFT_CONTROL_PORT") parse-long))]
        (socket/socket-transport {:port port}))))

(defn tool
  "Construct the one consolidated tool descriptor; clients discover commands through catalog."
  [entries transport]
  {:name "vectorcraft"
   :description "VectorCraft bridge. catalog lists engine commands/MCP tools/control methods; doctor diagnoses capabilities; call validates an engine command against the extracted reference catalog; control sends a catalogued control method (document.inspect, ui.render, app.export, ...). call and control need a running app (vectorcraft --control <port>)."
   :inputSchema {:type "object" :required ["command"] :additionalProperties false
                 :properties {"command" {:type "string" :enum ["catalog" "doctor" "call" "control"]}
                              "query" {:type "string" :description "Catalog section or exact engine command id."}
                              "engine_command" {:type "string" :description "Exact engine command id to execute."}
                              "method" {:type "string" :description "Control method for command=control (see catalog query=control)."}
                              "params" {:type "object" :description "Engine or control parameters."}
                              "id" {:description "Nonnegative integer or string request id."}}}
   :handler (fn [args]
              (let [command (or (get args "command") (:command args))
                    params (cond (contains? args "params") (get args "params")
                                 (contains? args :params) (:params args)
                                 :else {})
                    request-id (cond (contains? args "id") (get args "id")
                                     (contains? args :id) (:id args)
                                     :else 0)
                    outcome (case command
                              "catalog" (service/catalog entries (or (get args "query") (:query args)))
                              "doctor" (service/doctor entries transport)
                              "call" (service/call entries transport
                                                   (or (get args "engine_command") (:engine_command args))
                                                   params request-id)
                              "control" (service/control entries transport
                                                         (or (get args "method") (:method args))
                                                         params request-id)
                              {:error {:kind :vectorcraft/unknown-tool-command
                                       :hint "Choose catalog, doctor, call or control."}})]
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
                   :transport (resolve-transport cfg configuration)})
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
                            {:transport :absent :hint "Start vectorcraft --control <port> and set :control-port or VECTORCRAFT_CONTROL_PORT."})}))

(defn addon-ctor
  "Host manifest entry point. Config may inject :transport, but never requires one to mount."
  [config]
  (->VectorcraftAddon (atom {}) (or config {})))
