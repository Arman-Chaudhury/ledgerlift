#!/usr/bin/env bash
# End-to-end demo against a running ledgerlift (default: start one on H2 and stop it afterwards).
# Usage: ./demo.sh            # self-contained
#        BASE=http://localhost:8080 ./demo.sh   # against an already running instance (e.g. docker compose)
set -euo pipefail
cd "$(dirname "$0")"
KEY="${LEDGERLIFT_API_KEY:-dev-key}"
STARTED=0
if [ -z "${BASE:-}" ]; then
  BASE=http://localhost:8080
  JAR=$(ls target/ledgerlift-*.jar 2>/dev/null | head -1 || true)
  if [ -z "$JAR" ]; then echo ">> building jar"; ./mvnw -q -ntp package -DskipTests; JAR=$(ls target/ledgerlift-*.jar | head -1); fi
  echo ">> starting $JAR on H2"
  java -jar "$JAR" --server.port=8080 > /tmp/ledgerlift-demo.log 2>&1 &
  PID=$!; STARTED=1
  trap 'kill $PID 2>/dev/null || true' EXIT
  for i in $(seq 1 60); do curl -fs "$BASE/actuator/health" >/dev/null 2>&1 && break; sleep 1; done
fi
api() { curl -fsS -H "X-API-Key: $KEY" "$@"; }
j() { python3 -c "import sys,json; from decimal import Decimal; d=json.load(sys.stdin, parse_float=Decimal); print($1)"; }

echo; echo "== 1. upload the Q2 journals template (426 lines, 144 journals)"
B1=$(api -F file=@examples/demo_q2_journals.csv "$BASE/api/v1/imports")
ID1=$(echo "$B1" | j 'd["id"]'); echo "$B1" | j '"batch %s: %s, %s rows staged" % (d["id"], d["status"], d["rowCount"])'

echo; echo "== 2. re-upload the same bytes -> idempotent (same batch id, no new rows)"
api -F file=@examples/demo_q2_journals.csv "$BASE/api/v1/imports" | j '"batch %s again, %s rows" % (d["id"], d["rowCount"])'

echo; echo "== 3. validate"
api -X POST "$BASE/api/v1/imports/$ID1/validate" | j '"-> %s (%s errors, %s warnings)" % (d["batch"]["status"], d["errors"], d["warnings"])'

echo; echo "== 4. post (engine: ${LEDGERLIFT_POSTING_ENGINE:-java})"
api -X POST "$BASE/api/v1/imports/$ID1/post" | j '"-> %s via %s: %s journals, %s lines" % (d["batch"]["status"], d["engine"], d["journals"], d["lines"])'

echo; echo "== 5. a file with business errors -> REJECTED, with an error-correction CSV"
B2=$(api -F file=@examples/business_errors.csv "$BASE/api/v1/imports"); ID2=$(echo "$B2" | j 'd["id"]')
api -X POST "$BASE/api/v1/imports/$ID2/validate" | j '"-> %s (%s errors, %s warnings); rule codes: %s" % (d["batch"]["status"], d["errors"], d["warnings"], ", ".join(sorted({f["ruleCode"] for f in d["findings"]})))'
api "$BASE/api/v1/imports/$ID2/errors.csv" | head -4
echo "..."

echo; echo "== 6. reports: trial balance (Apr-26), html/csv/json from one data model"
api "$BASE/api/v1/reports/trial-balance?format=csv&period=Apr-26" | column -s, -t | head -12
api "$BASE/api/v1/reports/trial-balance" | j '"trial balance nets to %s across %s accounts" % (sum(r[d["columns"].index("net")] for r in d["rows"]), len(d["rows"]))'
api "$BASE/api/v1/reports/error-summary?batchId=$ID2&format=csv"

echo; echo "== 7. SOAP: poll batch $ID1 status through the WSDL-described endpoint"
curl -fsS -H "X-API-Key: $KEY" -H 'Content-Type: text/xml' "$BASE/ws" --data "<e:Envelope xmlns:e=\"http://schemas.xmlsoap.org/soap/envelope/\"><e:Body><getImportStatusRequest xmlns=\"http://ledgerlift.io/imports\"><batchId>$ID1</batchId></getImportStatusRequest></e:Body></e:Envelope>" | sed 's/></>\n</g' | grep -E "status|rowCount" | sed 's/^/   /'
echo; echo "WSDL: $BASE/ws/imports.wsdl   OpenAPI: $BASE/swagger-ui.html"
[ $STARTED = 1 ] && echo && echo ">> stopping demo instance"
