(ns hive-vectorcraft.stub
  "Injected in-memory test adapter and recording decorator; never opens a socket."
  (:require [hive-vectorcraft.port :as port]))

(defrecord StubTransport [response]
  port/ControlTransport
  (send-request [_ _] response))

(defn stub
  "Return a deterministic response adapter."
  [response]
  (->StubTransport response))

(defrecord RecordingTransport [delegate calls]
  port/ControlTransport
  (send-request [_ request]
    (swap! calls conj request)
    (port/send-request delegate request)))

(defn recording
  "Decorate an adapter; return [adapter calls-atom] for assertions."
  [delegate]
  (let [calls (atom [])]
    [(->RecordingTransport delegate calls) calls]))
