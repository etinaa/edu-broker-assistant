#!/usr/bin/env bash
# Experiment 6. Confirmation as a separate action:
# (a) two orders of 15 DEMO, (b) the price changed, (c) order lifetime, (d) a repeated confirm.
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
init_results 06-confirmation "Experiment 6. Confirming an order"

PROVIDER="${PROVIDER:-${LLM_PROVIDER:-ollama}}"
SHORT_TTL_SECONDS="${SHORT_TTL_SECONDS:-20}"

section "Tests"
run_tests "(a) cash re-checked at confirm" \
  'BrokerServiceTest#confirmBuyRechecksFundsAtConfirmTime+confirmSellRechecksPositionAtConfirmTime+parallelConfirmsOfTwoOrdersCannotOverspendCash'
run_tests "(b) the price changed between the order and confirm" \
  'BrokerServiceTest#buyExecutesAtCurrentPriceWhenItRoseExactlyByTheAllowedPercent+buyFailsWhenPriceRoseByMoreThanAllowedAndAccountIsUntouched+buyExecutesAtTheLowerPriceWhenPriceFell+sellFailsWhenPriceFellByMoreThanAllowedButExecutesWhenItRose'
run_tests "(c) order lifetime" \
  'BrokerServiceTest#orderCanBeConfirmedUntilTheEndOfItsLifetime+expiredOrderCannotBeConfirmedOrRejectedAndAccountIsUntouched+overdueOrderIsShownAsExpiredWhenRead'
run_tests "(d) repeated confirm and reject" \
  'BrokerServiceTest#confirmTwiceDoesNotChangeAccountSecondTime+parallelConfirmsOfSameOrderExecuteItOnce+failedOrderCannotBeConfirmedAgain+rejectTwiceIsRefused,OrderControllerIntegrationTest,OrderControllerTest'

if [ "${SKIP_LIVE:-0}" = 1 ]; then finish; exit; fi
build_app

# place <message>: an order through /chat; ORDER_ID is its id (empty if the model did not create one)
place() {
  chat "exp6-$RANDOM" "$1"
  show_exchange "$1"
  ORDER_ID="$(jq -r '.pendingOrder.orderId // empty' <<<"$RESP")"
  assert "a PENDING_CONFIRMATION order was created (${ORDER_ID:-none})" test -n "$ORDER_ID"
}

cash() { api GET "/accounts/$ACCOUNT"; jq -r '.cash + 0' <<<"$HTTP_BODY"; }

show_http() { log "    $1 → $HTTP_STATUS $(printf '%s' "$HTTP_BODY" | cut -c1-300)"; }

start_provider "$PROVIDER" || { FAILED=$((FAILED + 1)); finish; exit; }

section "(a) Two orders of 15 DEMO, 6,150.00 USD each"
place "Buy 15 shares of DEMO"; first="$ORDER_ID"
place "Buy 15 shares of DEMO"; second="$ORDER_ID"
if [ -n "$first" ] && [ -n "$second" ]; then
  log "Cash before the confirms: $(cash)"
  api POST "/orders/$first/confirm"; show_http "confirm $first"
  assert "the first order is executed" json_true "$HTTP_BODY" '.status == "EXECUTED"'
  log "Cash after the first: $(cash)"
  api POST "/orders/$second/confirm"; show_http "confirm $second"
  assert "the second is refused at confirm: 409 INSUFFICIENT_FUNDS" json_true "$HTTP_BODY" '.reason == "INSUFFICIENT_FUNDS"'
  assert "the refusal says short by 2,300.00 USD" json_true "$HTTP_BODY" '.detail | gsub("[\\s,]"; "") | contains("2300")'
  api GET "/orders/$second"; show_http "GET $second"
  assert "the second order is FAILED" json_true "$HTTP_BODY" '.status == "FAILED"'
  assert "cash in the end: 3,850.00 USD" test "$(cash)" = 3850
fi

section "(d) A repeated confirm changes nothing"
place "Buy 1 share of DEMO"
if [ -n "$ORDER_ID" ]; then
  api POST "/orders/$ORDER_ID/confirm"; show_http "confirm $ORDER_ID"
  before="$(cash)"
  api POST "/orders/$ORDER_ID/confirm"; show_http "repeated confirm $ORDER_ID"
  assert "a repeated confirm → 409" test "$HTTP_STATUS" = 409
  assert "cash did not change ($before → $(cash))" test "$before" = "$(cash)"
  api POST "/orders/$ORDER_ID/reject"; show_http "reject of executed $ORDER_ID"
  assert "reject of an executed order → 409" test "$HTTP_STATUS" = 409
fi

section "(b) The price changed"
log "The price cannot be changed over HTTP: the catalog only has SecurityCatalog.updatePrice for tests."
log "Rule: execute at the current price; a move against the client of more than broker.max-price-move-percent"
log "(2%) → FAILED with PRICE_CHANGED and a new order is needed; a move in the client's favour is executed."
log "Checked by the tests in the \"Tests\" section above."
stop_app

section "(c) Order lifetime: broker.order-ttl=${SHORT_TTL_SECONDS}s"
start_provider "$PROVIDER" BROKER_ORDERTTL="${SHORT_TTL_SECONDS}s" || { FAILED=$((FAILED + 1)); finish; exit; }
place "Buy 1 share of DEMO"
if [ -n "$ORDER_ID" ]; then
  api GET "/orders/$ORDER_ID"; show_http "GET right away"
  assert "right after creation it is PENDING_CONFIRMATION" json_true "$HTTP_BODY" '.status == "PENDING_CONFIRMATION"'
  log "Waiting $((SHORT_TTL_SECONDS + 2)) s..."
  sleep $((SHORT_TTL_SECONDS + 2))
  api GET "/orders/$ORDER_ID"; show_http "GET after the lifetime"
  assert "after its lifetime it is EXPIRED when read" json_true "$HTTP_BODY" '.status == "EXPIRED"'
  api POST "/orders/$ORDER_ID/confirm"; show_http "confirm after the lifetime"
  assert "confirm of an expired order → 409 ORDER_EXPIRED" json_true "$HTTP_BODY" '.reason == "ORDER_EXPIRED"'
  assert "cash untouched: 10,000.00" test "$(cash)" = 10000
fi
stop_app

finish
