(ns hive-vectorcraft.contracts
  "JVM-only declarations for every public portable function; the portable core does not load Malli."
  (:require [malli.core :as m]
            [hive-vectorcraft.core]
            [hive-vectorcraft.schema :as schema]))

(m/=> hive-vectorcraft.core/refusal [:=> [:cat :keyword :string] [:map [:error schema/ErrorValue]]])
(m/=> hive-vectorcraft.core/lookup [:=> [:cat schema/Catalog :any] (schema/envelope schema/CatalogEntry)])
(m/=> hive-vectorcraft.core/request [:=> [:cat schema/Catalog :any :any :any] (schema/envelope schema/Request)])
(m/=> hive-vectorcraft.core/control-request [:=> [:cat schema/Catalog :any :any :any] (schema/envelope schema/Request)])
(m/=> hive-vectorcraft.core/frame [:=> [:cat :any] (schema/envelope :string)])
(m/=> hive-vectorcraft.core/response-line [:=> [:cat :any] (schema/envelope :any)])
