#!/usr/bin/env bash
# Experiment 1. Skeleton and domain without an LLM: the BrokerService rules work and are checked without a model.
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
init_results 01-domain-without-llm "Experiment 1. Domain and rules without an LLM"

section "Unit tests of the rules"
run_tests "domain and BrokerService" \
  'BrokerServiceTest,AccountTest,OrderBookTest,SecurityCatalogTest,AccountRepositoryTest,BrokerPropertiesTest'
run_tests "HTTP layer without a model" 'AccountControllerTest,OrderControllerTest,OrderControllerIntegrationTest'

if [ "${SKIP_LIVE:-0}" = 1 ]; then finish; exit; fi

section "Live check without a model"
log "The model is never called: only /accounts, /securities/search and /orders are used."
build_app
# The ollama provider does not contact the server at startup, so this experiment does not need Ollama.
start_app no-llm LLM_PROVIDER=ollama || { log "The application did not start, log: $APP_LOG"; FAILED=$((FAILED + 1)); finish; exit; }

api GET /accounts/BRK-1
log '```'; log "GET /accounts/BRK-1 → $HTTP_STATUS $HTTP_BODY"; log '```'
assert "account BRK-1: 10,000.00 USD, TRNC 20, SMPB 100, DEMO 5" json_true "$HTTP_BODY" \
  '.cash == 10000 and .positions.TRNC == 20 and .positions.SMPB == 100 and .positions.DEMO == 5'

api GET /accounts/BRK-2
assert "another account BRK-2 with the BRK-1 header → 404 (got $HTTP_STATUS)" test "$HTTP_STATUS" = 404

saved_account="$ACCOUNT"; ACCOUNT=""
api GET /accounts/BRK-1
ACCOUNT="$saved_account"
assert "a request without X-Account-Id → 400 (got $HTTP_STATUS)" test "$HTTP_STATUS" = 400

section "Security search by the find_security rules"
search() {
  local query="$1" expr="$2" desc="$3"
  api GET /securities/search --get --data-urlencode "query=$query"
  log "    \"$query\" → $(jq -c '{match, tickers: [.securities[].ticker]}' <<<"$HTTP_BODY" 2>/dev/null || echo "$HTTP_STATUS $HTTP_BODY")"
  assert "$desc" json_true "$HTTP_BODY" "$expr"
}
search "trnc" '.match == "EXACT" and .securities[0].ticker == "TRNC"' "ticker in any case → EXACT TRNC"
search "US0000TRNP02" '.match == "EXACT" and .securities[0].ticker == "TRNP"' "ISIN → EXACT TRNP"
search "Sample Bank" '.match == "EXACT" and .securities[0].ticker == "SMPB"' "the only name match → EXACT SMPB"
search "  sample   LOGISTICS ltd " '.match == "EXACT" and .securities[0].ticker == "SMPL"' "case, spaces and dots do not matter → EXACT SMPL"
search "Training" '.match == "CANDIDATES" and ([.securities[].ticker] | sort) == ["TREN","TRNC","TRNP"]' "several name matches → CANDIDATES TRNC, TRNP, TREN"
search "Tesla" '.match == "NOT_FOUND" and (.securities | length) == 0' "nothing → NOT_FOUND"
search "TRN" '.match != "EXACT" or .securities[0].ticker != "TRNC"' "part of a ticker is not an exact match"

section "Orders: foreign and missing"
api GET /orders/ORD-999
assert "GET of a missing order → 404 (got $HTTP_STATUS)" test "$HTTP_STATUS" = 404
api POST /orders/ORD-999/confirm
assert "confirm of a missing order → 404 (got $HTTP_STATUS)" test "$HTTP_STATUS" = 404
log ""
log "An order cannot be created over HTTP without the model (there is no POST /orders): the tests above check placeOrder/confirm/reject."

finish
