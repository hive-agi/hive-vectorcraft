(ns hive-vectorcraft.core
  "Portable command vocabulary and control-channel frame values. No I/O, runtime dependencies or host-specific conditionals."
  (:require [clojure.string :as str]))

(defn refusal
  "A portable error value with an actionable next step."
  [kind hint]
  {:error {:kind kind :hint hint}})

(defn lookup
  "Find a command by its exact id, or refuse. Catalog is injected as a value."
  [catalog id]
  (if-let [entry (and (string? id) (first (filter #(= id (:id %)) (:engine catalog))))]
    {:ok entry}
    (refusal :vectorcraft/unknown-command
             (str "Unknown engine command " (pr-str id) "; run vectorcraft catalog to list valid ids."))))

(defn request
  "Validate an engine command and produce the unencoded control-channel request."
  [catalog id params request-id]
  (let [found (lookup catalog id)]
    (cond
      (:error found) found
      (not (map? params)) (refusal :vectorcraft/invalid-params "Pass params as an object/map (use {} for no params).")
      (not (or (string? request-id) (and (integer? request-id) (<= 0 request-id))))
      (refusal :vectorcraft/invalid-id "Pass a nonnegative integer or string request id.")
      :else {:ok {:id request-id :method "engine.execute" :params {:command id :params params}}})))

(defn control-request
  "Build a request for a catalogued control method (not an engine command)."
  [catalog method params request-id]
  (cond
    (not (and (string? method) (some #(= method %) (:control catalog))))
    (refusal :vectorcraft/unknown-method "Unknown control method; run vectorcraft catalog for allowed methods.")
    (not (map? params)) (refusal :vectorcraft/invalid-params "Pass params as an object/map.")
    (not (or (string? request-id) (and (integer? request-id) (<= 0 request-id))))
    (refusal :vectorcraft/invalid-id "Pass a nonnegative integer or string request id.")
    :else {:ok {:id request-id :method method :params params}}))

(defn frame
  "Wire-independent JSON-lines framing: accept only one JSON object line, reject embedded line breaks. Caller supplies its runtime's JSON encoder."
  [json-text]
  (if (and (string? json-text) (str/starts-with? json-text "{")
           (str/ends-with? json-text "}")
           (not (str/includes? json-text "\n")) (not (str/includes? json-text "\r")))
    {:ok (str json-text "\n")}
    (refusal :vectorcraft/invalid-frame "Encode one JSON object with method as a single line; remove line breaks.")))

(defn response-line
  "Validate the structural response after the transport's JSON decoder; preserve false and null results."
  [response]
  (let [present? (and (map? response) (or (contains? response :ok) (contains? response "ok")))
        status (if (contains? response :ok) (:ok response) (get response "ok"))]
    (cond
      (not (map? response)) (refusal :vectorcraft/invalid-response "Decode one JSON response object.")
      (not present?) (refusal :vectorcraft/invalid-response "Response must contain boolean ok.")
      (not (or (= true status) (= false status)))
      (refusal :vectorcraft/invalid-response "Response ok must be boolean.")
      (= true status)
      {:ok (if (contains? response :result) (:result response) (get response "result"))}
      :else (refusal :vectorcraft/upstream-error
                     (str "VectorCraft rejected the request: " (or (:error response) (get response "error") "unknown error"))))))
