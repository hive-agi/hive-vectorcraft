(ns hive-vectorcraft.port
  "Boundary port. A future cljw or native transport will implement the same operation. No transport is installed in wave 1.")

(defprotocol ControlTransport
  (send-request [transport request] "Send one validated control request and return a response value."))
