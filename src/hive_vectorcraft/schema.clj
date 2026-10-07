(ns hive-vectorcraft.schema
  "JVM value-object schemas for the portable vocabulary and boundary envelopes."
  (:require [malli.core :as m]))

(def CatalogEntry
  [:map [:id :string] [:label :string] [:params {:optional true} :string]])

(def Catalog
  [:map [:engine [:vector CatalogEntry]]
   [:control [:vector :string]]
   [:mcp {:optional true} [:vector :map]]
   [:reference-revision {:optional true} :string]])

(def Request
  [:map [:id [:or :string [:and :int [:fn #(<= 0 %)]]]]
   [:method :string] [:params :map]])

(def ErrorValue
  [:map [:kind :keyword] [:hint :string]])

(def SocketConfig
  [:map [:host :string] [:port [:int {:min 1 :max 65535}]]
   [:connect-ms pos-int?] [:timeout-ms pos-int?]])

(defn envelope [value]
  [:or [:map [:ok value]] [:map [:error ErrorValue]]])

(m/=> envelope [:=> [:cat :any] [:vector :any]])
