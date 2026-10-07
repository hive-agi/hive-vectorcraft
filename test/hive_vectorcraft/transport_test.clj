(ns hive-vectorcraft.transport-test
  "Live control-channel transport: control dispatch, transport resolution, and a loopback round trip."
  (:require [clojure.test :refer [deftest is]]
            [clojure.data.json :as json]
            [clojure.test.check.generators :as gen]
            [hive-test.trifecta :refer [deftrifecta]]
            [hive-vectorcraft.addon :as addon]
            [hive-vectorcraft.port :as port]
            [hive-vectorcraft.service :as service]
            [hive-vectorcraft.stub :as stub]
            [hive-vectorcraft.transport.socket :as socket]
            [hive-addon.protocol :as protocol])
  (:import (hive_vectorcraft.transport.socket SocketTransport)
           (java.io BufferedReader InputStreamReader PrintWriter)
           (java.net ServerSocket)))

(def entries {:engine [{:id "shape.rectangle" :label "Rectangle"}]
              :mcp []
              :control ["engine.execute" "document.inspect" "ui.render"]
              :reference-revision "65c5953"})

(defn- transport-view
  "Project a resolved transport to comparable data: a socket transport becomes its config."
  [t]
  (if (instance? SocketTransport t) (:config t) t))

(deftrifecta control-contract service/control
  {:golden-path "test/golden/service-control.edn"
   :cases {:valid [entries (stub/stub {:ok true :result {:layers 1}}) "document.inspect" {} 3]
           :missing [entries nil "document.inspect" {} 3]
           :unknown [entries nil "app.format-disk" {} 3]
           :upstream [entries (stub/stub {:ok false :error "no document"}) "ui.render" {} 4]}
   :apply? true
   :gen (gen/tuple (gen/return entries) (gen/return nil)
                   (gen/elements ["document.inspect" "ui.render" "bad"]) (gen/return {}) gen/nat)
   :pred #(or (contains? % :ok) (contains? % :error)) :num-tests 80
   :mutations [["skip-catalog-check" (fn [_ _ method params id]
                                       {:ok {:id id :method method :params params}})]]})

(deftrifecta resolve-transport-contract addon/resolve-transport
  {:golden-path "test/golden/resolve-transport.edn"
   :cases {:injected [{:transport :injected-port} {:control-port 7979}]
           :configured [{} {:control-port 7979}]
           :init-config [{:control-port 8080} {}]
           :absent [{} {}]}
   :apply? true :xf transport-view
   :gen (gen/tuple (gen/return {}) (gen/fmap #(hash-map :control-port %) (gen/choose 1 65535)))
   :pred #(= "127.0.0.1" (:host (transport-view %))) :num-tests 80
   :mutations [["ignore-config" (fn [_cfg _configuration] nil)]]})

(deftrifecta socket-transport-contract socket/socket-transport
  {:golden-path "test/golden/socket-transport.edn"
   :cases {:default [{}] :port [{:port 9001}] :timeout [{:port 7979 :timeout-ms 500}]}
   :apply? true :xf :config
   :gen (gen/fmap #(hash-map :port %) (gen/choose 1 65535))
   :pred #(and (= "127.0.0.1" (:host (:config %))) (pos-int? (:connect-ms (:config %)))) :num-tests 80
   :mutations [["drop-overrides" (fn [_config] (socket/->SocketTransport socket/default-config))]]})

(defn- serve-lines!
  "Answer `n` connections on an ephemeral loopback port; each reply echoes the request method."
  [^ServerSocket server n seen]
  (future
    (dotimes [_ n]
      (with-open [s (.accept server)]
        (let [in (BufferedReader. (InputStreamReader. (.getInputStream s)))
              out (PrintWriter. (.getOutputStream s) true)
              request (json/read-str (.readLine in))]
          (swap! seen conj request)
          (.println out (json/write-str {:id (get request "id") :ok true
                                         :result {:method (get request "method")}})))))))

(deftest loopback-round-trip
  (with-open [server (ServerSocket. 0)]
    (let [seen (atom [])
          _ (serve-lines! server 2 seen)
          instance (addon/addon-ctor {:control-port (.getLocalPort server)})
          _ (protocol/initialize! instance {})
          handler (:handler (first (protocol/tools instance)))]
      (is (= :ok (:status (protocol/health instance))))
      (is (= "{:method \"document.inspect\"}"
             (-> (handler {"command" "control" "method" "document.inspect"}) :content first :text)))
      (is (= {:ok {:method "engine.execute"}}
             (service/call (:catalog @(:state instance)) (socket/socket-transport {:port (.getLocalPort server)})
                           (-> instance :state deref :catalog :engine first :id) {} 2)))
      (is (= ["document.inspect" "engine.execute"] (mapv #(get % "method") @seen)))
      (protocol/shutdown! instance))))

(deftest refused-connection-is-a-value
  (let [outcome (service/control entries (socket/socket-transport {:port 1 :connect-ms 300})
                                 "document.inspect" {} 1)]
    (is (= :vectorcraft/transport-failed (get-in outcome [:error :kind])))
    (is (satisfies? port/ControlTransport (socket/socket-transport {})))))
