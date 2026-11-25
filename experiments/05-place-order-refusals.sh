#!/usr/bin/env bash
# Experiment 5. place_order and refusals: questions 5–10 on both models, and the numbers in the model's text
# compared with the numbers the code computed (pendingOrder or a place_order refusal).
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
. "$EXP_DIR/lib/questions.sh"
init_results 05-place-order-refusals "Experiment 5. place_order, refusals and the numbers in the answer"

section "Tests"
run_tests "place_order and the order rules" \
  'BrokerToolsPlaceOrderTest,SingleOrderGuardTest,BrokerServiceTest#place*'
run_tests "order and refusal through /chat" \
  'ChatIntegrationTest#buyFlowCreatesPendingOrderThatTheUserConfirms+refusalIsAnOrdinaryAnswerWithoutPendingOrder,AssistantServiceTest#createdOrderBecomesPendingOrder+refusedOrderIsNotPending+onlyOneOrderPerMessageSoTheShownOrderIsTheOnlyConfirmableOne'

if [ "${SKIP_LIVE:-0}" = 1 ]; then finish; exit; fi
build_app

for provider in ${PROVIDERS:-claude ollama}; do
  section "Questions 5–10 on $provider ($(provider_model "$provider"))"
  start_provider "$provider" || continue
  run_questions "$provider" 5 6 7 8 9 10
  api GET "/accounts/$ACCOUNT"
  assert "cash and positions did not change after the run: the orders only wait for confirmation" json_true "$HTTP_BODY" \
    '.cash == 10000 and .positions.TRNC == 20 and .positions.SMPB == 100 and .positions.DEMO == 5'
  stop_app
done

for provider in ${PROVIDERS:-claude ollama}; do
  if [ -f "$RESULTS_DIR/table-$provider.md" ]; then print_question_table "$provider"; fi
done

section "Do the numbers in the model's text match the numbers from the code"
log "Code numbers: price/total/cashAfter from pendingOrder, or needed/available/shortfall from a place_order refusal."
log "Spaces, no-break spaces and commas are removed from the text before the search (4,100.00 → 4100.00)."
log ""
log "| Model | Message | Code numbers | Missing in the text |"
log "|---|---|---|---|"
if [ -f "$RESULTS_DIR/chats.jsonl" ]; then
  jq -r '
    select(.status == "200")
    | (.response.answer // "" | gsub("[\\s  ,]"; "")) as $text
    | (if .response.pendingOrder then [.response.pendingOrder | .price, .total, .cashAfter]
       else [.trace.steps[]?.calls[]? | select(.tool == "place_order") | .result | fromjson?
             | select(.result == "REFUSED") | .needed, .available, .shortfall | select(. != null)]
       end | map(. + 0 | tostring)) as $nums
    | select($nums | length > 0)
    | [$nums[] | select(. as $n | $text | contains($n) | not)] as $missing
    | "| \(.trace.model // "—") | \(.message) | \($nums | join(", ")) | \(if $missing == [] then "—" else ($missing | join(", ")) end) |"
  ' "$RESULTS_DIR/chats.jsonl" | tee "$RESULTS_DIR/numbers.md" | tee -a "$REPORT"
  mismatches="$(grep -vc '| — |$' "$RESULTS_DIR/numbers.md" || true)"
  log ""
  log "Mismatches: $mismatches. On a mismatch the response structure (pendingOrder) is right, not the text."
fi

finish
