(ns hive-vectorcraft.service
  "Promote request values into catalog results and boundary dispatch; no transport is created here."
  (:require [clojure.string :as str]
            [hive-vectorcraft.core :as core]
            [hive-vectorcraft.port :as port]))

(defn catalog
  "Describe the pinned vocabulary; query selects engine, mcp, control or one exact command id."
  [entries query]
  (case query
    "engine" {:ok (:engine entries)}
    "mcp" {:ok (:mcp entries)}
    "control" {:ok (:control entries)}
    nil {:ok {:revision (:reference-revision entries)
              :engine (count (:engine entries)) :mcp (count (:mcp entries))
              :control (count (:control entries))}}
    (core/lookup entries query)))

(defn doctor
  "Report installed capability honestly, including the missing transport's remedy."
  [entries transport]
  {:ok {:catalog {:revision (:reference-revision entries)
                 :engine (count (:engine entries)) :mcp (count (:mcp entries))
                 :control (count (:control entries))}
        :transport (if transport :injected :absent)
        :hint (if transport "Injected transport ready."
                  "No live transport in wave 1; inject a ControlTransport. A future cljw loopback client or Rust cdylib adapter can implement this port.")}})

(defn call
  "Validate first; send only approved engine requests through an injected port."
  [entries transport command params request-id]
  (let [built (core/request entries command params request-id)]
    (cond
      (:error built) built
      (nil? transport) (core/refusal :vectorcraft/no-transport
                                    "Inject a ControlTransport; wave 1 includes a recording stub only, not a live client.")
      :else (try (core/response-line (port/send-request transport (:ok built)))
                 (catch Exception e
                   (core/refusal :vectorcraft/transport-failed
                                 (str "Check the injected transport: " (ex-message e))))))))

(defn control
  "Validate a catalogued control method (document.inspect, ui.render, app.export, ...) and send it through the injected port."
  [entries transport method params request-id]
  (let [built (core/control-request entries method params request-id)]
    (cond
      (:error built) built
      (nil? transport) (core/refusal :vectorcraft/no-transport
                                     "Start VectorCraft with --control <port> and set :control-port (or VECTORCRAFT_CONTROL_PORT).")
      :else (try (core/response-line (port/send-request transport (:ok built)))
                 (catch Exception e
                   (core/refusal :vectorcraft/transport-failed
                                 (str "Check that VectorCraft is running with --control: " (ex-message e))))))))
