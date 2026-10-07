#!/usr/bin/env bash
# Run the same deterministic oracle on all four runtimes, sequentially.
set -euo pipefail
repo=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo"
seconds=${PORTABILITY_TIMEOUT:-120}
[[ $seconds =~ ^[1-9][0-9]*$ ]] || { echo 'PORTABILITY_TIMEOUT must be positive' >&2; exit 2; }
report=${PORTABILITY_REPORT_DIR:-$(mktemp -d -t vectorcraft-portability.XXXXXX)}
mkdir -p "$report"
printf 'host\tstatus\tseconds\n' > "$report/results.tsv"
for host in "${@:-jvm cljw cljrs cljs}"; do
  start=$SECONDS
  status=passed
  case "$host" in
    jvm) command -v bb >/dev/null || { echo 'Missing bb nREPL client' >&2; exit 2; }
         bb -e '(require (quote [nrepl.core :as n])) (with-open [conn (n/connect :port (parse-long (or (System/getenv "VECTORCRAFT_REPL_PORT") "7928")))] (doseq [r (n/message (n/client conn 90000) {:op "eval" :code "(do (require (quote portability) :reload) (portability/-main))"})] (when (or (:err r) (:ex r)) (throw (ex-info "JVM oracle error" r))) (when (:out r) (print (:out r)))))' > "$report/jvm.log" 2>&1 || status=failed ;;
    cljw) command -v cljw >/dev/null || { echo 'Missing cljw' >&2; exit 2; }
          timeout "${seconds}s" cljw -cp src:dev dev/portability.cljc > "$report/cljw.log" 2>&1 || status=failed ;;
    cljrs) binary=${CLJRS:-/home/klein/PP/clojurust/target/debug/cljrs}
           [[ -x "$binary" ]] || { echo "Missing cljrs: $binary" >&2; exit 2; }
           timeout "${seconds}s" "$binary" run dev/portability.cljc --src-path src --src-path dev > "$report/cljrs.log" 2>&1 || status=failed ;;
    cljs) command -v node >/dev/null || { echo 'Missing Node' >&2; exit 2; }
          timeout "${seconds}s" clojure -M:cljs -m shadow.cljs.devtools.cli release portability > "$report/cljs-build.log" 2>&1 || status=failed
          if [[ $status == passed ]]; then
            timeout "${seconds}s" node target/portability.js > "$report/cljs.log" 2>&1 || status=failed
          fi ;;
    *) echo "Unknown host $host" >&2; exit 2 ;;
  esac
  printf '%s\t%s\t%s\n' "$host" "$status" "$((SECONDS-start))" >> "$report/results.tsv"
  echo "$host: $status ($((SECONDS-start))s)"
  if [[ $status != passed ]]; then tail -20 "$report/$host.log" 2>/dev/null || true; exit 1; fi
done
echo "Report: $report"
