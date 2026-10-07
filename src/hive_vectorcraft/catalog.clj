(ns hive-vectorcraft.catalog
  "Read the extracted, version-pinned reference vocabulary at the JVM boundary."
  (:require [clojure.edn :as edn]))

(defn load-catalog
  "Load the pinned catalog from a classpath resource; no network or native process."
  []
  (if-let [resource (clojure.java.io/resource "hive_vectorcraft/catalog.edn")]
    (edn/read-string (slurp resource))
    {:error {:kind :vectorcraft/catalog-missing
             :hint "Package resources/hive_vectorcraft/catalog.edn; rerun dev/extract_catalog.clj."}}))
