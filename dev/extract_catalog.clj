(ns extract-catalog
  "Extract command metadata from VectorCraft's Rust registry and MCP tool declarations.
   Run from this repository: clojure -M dev/extract_catalog.clj <reference-root>."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]))

(defn calls [text marker]
  (loop [at 0 found []]
    (if-let [start (str/index-of text marker at)]
      (let [open (+ start (count marker))
            end (loop [i open depth 1 quoted false escaped false]
                  (when (= i (count text))
                    (throw (ex-info "Unclosed Rust invocation" {:marker marker :start start})))
                  (let [ch (.charAt text i)
                        depth' (if quoted depth (case ch \( (inc depth) \) (dec depth) depth))
                        quoted' (if (and (= ch \") (not escaped)) (not quoted) quoted)
                        escaped' (and quoted (= ch \\) (not escaped))]
                    (if (zero? depth') i
                        (recur (inc i) depth' quoted' escaped'))))]
        (recur (inc end) (conj found (subs text open end))))
      found)))

(defn fields [body]
  (loop [i 0 start 0 depth 0 quoted false escaped false result []]
    (if (= i (count body))
      (conj result (str/trim (subs body start)))
      (let [ch (.charAt body i)
            delim? (and (not quoted) (zero? depth) (= ch \,))
            depth' (if quoted depth (case ch \( (inc depth) \[ (inc depth) \{ (inc depth)
                                            \) (dec depth) \] (dec depth) \} (dec depth) depth))
            quoted' (if (and (= ch \") (not escaped)) (not quoted) quoted)
            escaped' (and quoted (= ch \\) (not escaped))]
        (recur (inc i) (if delim? (inc i) start) depth' quoted' escaped'
               (if delim? (conj result (str/trim (subs body start i))) result))))))

(defn literal [s]
  (try (edn/read-string s) (catch Exception _ s)))

(defn split-on [text delimiter]
  (loop [start 0 pieces []]
    (if-let [at (str/index-of text delimiter start)]
      (recur (+ at (count delimiter)) (conj pieces (subs text start at)))
      (conj pieces (subs text start)))))

(defn backtick-values [text]
  (loop [at 0 values []]
    (if-let [open (str/index-of text "`" at)]
      (if-let [close (str/index-of text "`" (inc open))]
        (recur (inc close) (conj values (subs text (inc open) close)))
        values)
      values)))

(defn engine [root]
  (let [dir (io/file root "crates/engine/src/cmd")]
    (->> (file-seq dir)
         (filter #(and (.isFile %) (str/ends-with? (.getName %) ".rs")))
         (mapcat (fn [file]
                   (for [call (calls (slurp file) "cmd!(")
                         :let [parts (fields call)]
                         :when (and (<= 7 (count parts)) (string? (literal (first parts)))
                                    (str/starts-with? (first parts) "\""))]
                     {:id (literal (nth parts 0)) :label (literal (nth parts 1))
                      :params (literal (nth parts 4))
                      :source (str "crates/engine/src/cmd/" (.getName file))})))
         (sort-by :id) vec)))

(defn mcp [root]
  (let [file (io/file root "crates/mcp/src/tools.rs")]
    (->> (calls (slurp file) "tool(")
         (keep (fn [body]
                 (let [parts (fields body)]
                   (when (and (<= 5 (count parts)) (str/starts-with? (first parts) "\""))
                     {:name (literal (first parts)) :title (literal (second parts))
                      :description (literal (nth parts 2))
                      ;; Rust composes schema expressions dynamically. Retain their exact
                      ;; expression; do not misrepresent this as evaluated JSON Schema.
                      :input-schema-source (nth parts 3)
                      :source "crates/mcp/src/tools.rs"})))) vec)))

(defn control [root]
  (let [lines (str/split-lines (slurp (io/file root "docs/control-protocol.md")))
        rows (->> lines (drop-while #(not (str/starts-with? % "| Method |")))
                  (drop 2) (take-while #(str/starts-with? % "|"))
                  (take-while #(not (str/includes? % " with `useArtboards`"))))]
    (->> rows
         (mapcat (fn [row]
                   (let [cell (second (split-on row "|"))
                         methods (backtick-values cell)
                         prefix (first methods)]
                     (map (fn [method]
                            (if (and (str/starts-with? method ".")
                                     (str/includes? prefix "."))
                              (str (subs prefix 0 (inc (str/last-index-of prefix "."))) (subs method 1))
                              method)) methods))))
         distinct sort vec)))

(defn -main [root]
  (when-not root (throw (ex-info "Supply VectorCraft reference checkout root" {})))
  (let [catalog {:reference-revision "65c5953" :engine (engine root)
                 :mcp (mcp root) :control (control root)}]
    (when (or (empty? (:engine catalog)) (empty? (:mcp catalog)) (empty? (:control catalog)))
      (throw (ex-info "Incomplete extraction" (update-vals (dissoc catalog :reference-revision) count))))
    (with-open [writer (io/writer "resources/hive_vectorcraft/catalog.edn")]
      (binding [*out* writer] (pprint/pprint catalog)))
    (println "Extracted" (count (:engine catalog)) "engine commands,"
             (count (:mcp catalog)) "MCP tools," (count (:control catalog)) "control methods")))

(apply -main *command-line-args*)
