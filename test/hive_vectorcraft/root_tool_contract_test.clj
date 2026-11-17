(ns hive-vectorcraft.root-tool-contract-test
  (:require [hive-addon.tool-contract.test :refer [deftest-root-tools]]
            [hive-addon.protocol :as proto]
            [hive-vectorcraft.addon :as a]))

(deftest-root-tools root-tools
  (let [addon (a/addon-ctor {})]
    (proto/initialize! addon {})
    (proto/tools addon)))
