#!/usr/bin/env bash
# Experiment 10. Optional extras on both models:
#   A. the broker fee (broker.fee-percent=2, broker.min-fee=1): the totals in pendingOrder and in the refusals,
#      the fee charged on confirm, and who computes "with all my money" once the fee changes the answer (52, not 53);
#   B. "did you mean" for typos: suggestions without the model, then no order on a guess and an order after "Yes";
#   C. POST /chat/stream: the order of the events, the same response as /chat, the time to the first event,
#      a busy session as an error event, 404 and 400 before the stream.
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
. "$EXP_DIR/lib/questions.sh"
init_results 10-optional-extras "Experiment 10. Broker fee, \"did you mean\" and streaming over SSE"

FEE_PERCENT="${FEE_PERCENT:-2}"
MIN_FEE="${MIN_FEE:-1}"

JQ_PRELUDE="$JQ_PRELUDE"'
def sugg: [$r.suggestions[]?.ticker] | sort;
'

section "Tests"
run_tests "broker fee" 'BrokerServiceFeeTest,BrokerPropertiesTest,TradeCostTest'
run_tests "\"did you mean\"" \
  'TypoSuggestionsTest,BrokerToolsFindSecurityTest,AmbiguityGuardTest,GroundedExecutorTest,ChatIntegrationTest#aTypo*+searchingForTheSuggestedTicker*'
run_tests "streaming over SSE" \
  'ChatStreamsTest,ToolLoopTest#theListener*+aListener*,ChatControllerTest#stream*+busySessionInAStream*+providerFailureInAStream*+aBugInAStream*,ChatIntegrationTest#streamed*+aGuardRefusalIsStreamed*+aStreamedMessage*'

if [ "${SKIP_LIVE:-0}" = 1 ]; then finish; exit; fi
build_app

# ---------------------------------------------------------------------------------------------------------------
# Helpers

# ask <label> <session> <message> <expected> <jq check>: one exchange with an outcome check and a table row
ask() {
  local label="$1" session="$2" msg="$3" expect="$4" check="$5" verdict=FAIL s ti to c l tl qty fee_in_text
  chat "$session" "$msg"
  show_exchange "$msg"
  if [ "$CHAT_STATUS" = 200 ] && eval_check "$check"; then verdict=PASS; else FAILED=$((FAILED + 1)); fi
  log "Outcome by the automatic check: **$verdict**"
  log ""
  IFS=$'\t' read -r s ti to c l tl <<<"$(trace_numbers)" || true
  qty="$(jq -r '[.steps[]?.calls[]? | select(.tool == "place_order") | (.arguments | fromjson? // .) | .quantity? // "?" | tostring] | join(" → ") | if . == "" then "—" else . end' <<<"$TRACE")"
  fee_in_text="$(jq -r '(.answer // "" | gsub("[\\s  ,]"; "")) as $t | (.pendingOrder.fee // 0) as $fee
    | if $fee > 0 then (if $t | contains($fee | tostring) then "yes" else "no" end) else "—" end' <<<"$RESP")"
  printf '| %s | %s | %s | %s | %s | %s | %s | %s |\n' "$msg" "$expect" "$verdict" "${tl:-—}" "$qty" "$fee_in_text" \
    "$(awk -v x="$CHAT_MS" 'BEGIN { printf "%.1f", x / 1000 }')" \
    "$(jq -r '.answer // .detail // .error // ""' <<<"$RESP" | tr '\n|' '  ' | cut -c1-140)" >>"$RESULTS_DIR/table-$label.md"
}

print_ask_table() {
  section "Table: $1"
  log "| Message | Expected | Outcome | Tools | place_order quantities | Fee in the text | HTTP, s | Answer |"
  log "|---|---|---|---|---|---|---|---|"
  tee -a "$REPORT" <"$RESULTS_DIR/table-$1.md"
}

session_id() { printf '%s-%s-%s' "$1" "$2" "$RANDOM" | tr -c 'A-Za-z0-9_-' '_' | cut -c1-64; }

# stream_raw <prefix> <sessionId> <message>: POST /chat/stream; headers into <prefix>.headers, every received line
# with the milliseconds since the request into <prefix>.lines. Safe to run in the background.
stream_raw() {
  local prefix="$1" body start
  body="$(jq -nc --arg s "$2" --arg m "$3" '{sessionId: $s, message: $m}')"
  local args=(-sN --max-time "$CHAT_TIMEOUT" -D "$prefix.headers" -X POST "$BASE_URL/chat/stream"
              -H 'Content-Type: application/json' -d "$body")
  if [ -n "$ACCOUNT" ]; then args+=(-H "X-Account-Id: $ACCOUNT"); fi
  start="$(now_ms)"
  curl "${args[@]}" | START="$start" perl -MTime::HiRes=time -ne \
    'BEGIN { $| = 1 } printf "%d\t%s", time() * 1000 - $ENV{START}, $_' >"$prefix.lines" || true
}

# stream_parse <prefix> → STREAM_STATUS, STREAM_TYPE and STREAM_EVENTS: [{ms, event, data}], ms of the first line
stream_parse() {
  local prefix="$1"
  STREAM_STATUS="$(awk 'NR == 1 { print $2 }' "$prefix.headers" 2>/dev/null || true)"
  STREAM_TYPE="$(grep -i '^content-type:' "$prefix.headers" 2>/dev/null | tail -1 | cut -d' ' -f2- | tr -d '\r' || true)"
  STREAM_EVENTS="$(awk -F'\t' '
      { ms = $1; line = substr($0, length($1) + 2); sub(/\r$/, "", line) }
      line == "" { if (ev != "" || data != "") print first "\t" (ev == "" ? "message" : ev) "\t" data
                   ev = ""; data = ""; first = ""; next }
      first == "" { first = ms }
      line ~ /^event:/ { ev = substr(line, 7); sub(/^ /, "", ev) }
      line ~ /^data:/ { d = substr(line, 6); sub(/^ /, "", d); data = data d }
      END { if (ev != "" || data != "") print first "\t" (ev == "" ? "message" : ev) "\t" data }
    ' "$prefix.lines" | jq -Rn '[inputs | split("\t") | {ms: (.[0] | tonumber), event: .[1], data: (.[2] | fromjson? // .)}]')"
  jq -c '.[]' <<<"$STREAM_EVENTS" >>"$RESULTS_DIR/$(basename "$prefix").events.jsonl"
}

show_stream() {
  log "HTTP $STREAM_STATUS, $STREAM_TYPE"
  log ""
  log '```'
  jq -r '.[] | "\(.ms / 1000 * 10 | floor / 10) s  \(.event)  \(.data | tojson | .[0:220])"' <<<"$STREAM_EVENTS" | tee -a "$REPORT"
  log '```'
  log ""
}

# ---------------------------------------------------------------------------------------------------------------

STREAM_SUMMARY="$RESULTS_DIR/stream-summary.md"
: >"$STREAM_SUMMARY"

for provider in ${PROVIDERS:-claude ollama}; do
  label="$provider"

  # --- A. Broker fee ---------------------------------------------------------------------------------------------
  section "A. Broker fee on $provider ($(provider_model "$provider")): fee-percent=$FEE_PERCENT, min-fee=$MIN_FEE"
  start_provider "$provider" BROKER_FEEPERCENT="$FEE_PERCENT" BROKER_MINFEE="$MIN_FEE" || continue
  : >"$RESULTS_DIR/table-fee-$label.md"

  ask "fee-$label" "$(session_id fee1 "$label")" "Buy 30 shares of DEMO" \
    "refused: 12,546.00 with the fee 246.00, short by 2,546.00" \
    'po == null and (num | test("2546"))'
  ask "fee-$label" "$(session_id fee2 "$label")" "Sell 10 TRNC" \
    "order SELL 10 × 185.25, fee 37.05, receives 1,815.45" \
    'po.side == "SELL" and po.ticker == "TRNC" and po.quantity == 10 and po.fee == 37.05 and po.total == 1815.45 and po.cashAfter == 11815.45'
  ask "fee-$label" "$(session_id fee3 "$label")" "Buy TRNC with all my money" \
    "52 shares: 9,633.00 + fee 192.66 = 9,825.66 (53 would be 10,014.62)" \
    '(po.ticker == "TRNC" and po.quantity == 52 and po.total == 9825.66) or (po == null and (num | test("52")))'
  ask "fee-$label" "$(session_id fee4 "$label")" "Buy 10 shares of DEMO" \
    "order 10 × 410.00 = 4,100.00 + fee 82.00 = 4,182.00, 5,818.00 left" \
    'po.ticker == "DEMO" and po.quantity == 10 and po.fee == 82 and po.total == 4182 and po.cashAfter == 5818 and po.status == "PENDING_CONFIRMATION"'

  order_id="$(jq -r '.pendingOrder.orderId // empty' <<<"$RESP")"
  log "Confirming the last order without the model: POST /orders/$order_id/confirm"
  if [ -n "$order_id" ]; then
    api POST "/orders/$order_id/confirm"
    log "    $HTTP_STATUS $(cut -c1-300 <<<"$HTTP_BODY")"
    assert "confirm → 200 EXECUTED, executedFee 82.00, executedTotal 4,182.00" json_true "$HTTP_BODY" \
      '.status == "EXECUTED" and .executedFee == 82 and .executedTotal == 4182'
    api GET "/accounts/$ACCOUNT"
    assert "the account paid the amount and the fee: cash 5,818.00, DEMO 15" json_true "$HTTP_BODY" \
      '.cash == 5818 and .positions.DEMO == 15'
  else
    assert "the DEMO order exists, so it can be confirmed" false
  fi
  print_ask_table "fee-$label"
  stop_app

  # --- B. "Did you mean" ------------------------------------------------------------------------------------------
  section "B. \"Did you mean\" on $provider"
  start_provider "$provider" AGENT_SESSIONBUSYTIMEOUT=100ms || continue
  : >"$RESULTS_DIR/table-typo-$label.md"

  log "Search without the model, GET /securities/search:"
  api GET "/securities/search?query=Trainig"
  log "    Trainig → $(jq -c '{match, suggestions: [.suggestions[].ticker]}' <<<"$HTTP_BODY")"
  assert "Trainig → NOT_FOUND, suggestions TREN, TRNC, TRNP" json_true "$HTTP_BODY" \
    '.match == "NOT_FOUND" and ([.suggestions[].ticker] | sort) == ["TREN","TRNC","TRNP"]'
  api GET "/securities/search?query=DEMA"
  log "    DEMA → $(jq -c '{match, suggestions: [.suggestions[].ticker]}' <<<"$HTTP_BODY")"
  assert "DEMA → NOT_FOUND, suggestion DEMO" json_true "$HTTP_BODY" \
    '.match == "NOT_FOUND" and [.suggestions[].ticker] == ["DEMO"]'
  api GET "/securities/search?query=Tesla"
  log "    Tesla → $(jq -c '{match, suggestions: [.suggestions[].ticker]}' <<<"$HTTP_BODY")"
  assert "Tesla → NOT_FOUND without suggestions" json_true "$HTTP_BODY" '.match == "NOT_FOUND" and .suggestions == []'
  log ""

  ask "typo-$label" "$(session_id typo1 "$label")" "How many Trainig do I have?" \
    "the three Training offered (suggestions or candidates), no guess" \
    '(sugg == ["TREN","TRNC","TRNP"] or cand == ["TREN","TRNC","TRNP"]) and po == null'
  session="$(session_id typo2 "$label")"
  ask "typo-$label" "$session" "Buy 10 DEMA" \
    "asks about DEMO (suggestion DEMO), no order" \
    'po == null and (ans | test("demo"))'
  ask "typo-$label" "$session" "Yes" \
    "after the user's yes: order DEMO 10" \
    'po.ticker == "DEMO" and po.quantity == 10 and po.status == "PENDING_CONFIRMATION"'
  ask "typo-$label" "$(session_id typo3 "$label")" "Buy Tesla shares" \
    "not found, no suggestions, no order" \
    'po == null and sugg == []'
  print_ask_table "typo-$label"

  # --- C. Streaming -----------------------------------------------------------------------------------------------
  section "C. POST /chat/stream on $provider"
  msg="Buy 10 shares of DEMO"
  prefix="$RESULTS_DIR/stream-$label"
  stream_raw "$prefix" "$(session_id stream "$label")" "$msg"
  stream_parse "$prefix"
  log "**Message:** $msg"
  show_stream
  events="$STREAM_EVENTS"
  assert "HTTP 200, text/event-stream" test "$STREAM_STATUS:${STREAM_TYPE%%;*}" = "200:text/event-stream"
  assert "the last event is the only response; only tool_call and tool_result come before it" json_true "$events" \
    'length > 0 and .[-1].event == "response" and ([.[:-1][].event] | all(. == "tool_call" or . == "tool_result"))'
  assert "at least one tool call; every tool_call has its tool_result, in the same order" json_true "$events" \
    '([.[] | select(.event == "tool_call") | .data.tool]) as $c | ([.[] | select(.event == "tool_result") | .data.tool]) as $r
     | ($c | length) > 0 and $c == $r'
  assert "tool_call carries only the tool, tool_result has no result: nothing of a message that may be rolled back" json_true "$events" \
    '([.[] | select(.event == "tool_call") | .data | keys] | all(. == ["tool"]))
     and ([.[] | select(.event == "tool_result") | .data | has("result")] | any | not)'
  response="$(jq -c '.[-1].data' <<<"$events")"
  assert "the response has the order DEMO 10 × 410.00 = 4,100.00, pending" json_true "$response" \
    '.pendingOrder.ticker == "DEMO" and .pendingOrder.quantity == 10 and .pendingOrder.total == 4100 and .pendingOrder.status == "PENDING_CONFIRMATION"'

  trace_id="$(jq -r '.traceId // empty' <<<"$response")"
  api GET "/traces/$trace_id"
  trace_body="$HTTP_BODY"
  [ "$HTTP_STATUS" = 200 ] || trace_body='{}'
  assert "the streamed tools are the tools in the trace of the response" json_true \
    "$(jq -nc --argjson e "$events" --argjson t "$trace_body" '{e: $e, t: $t}')" \
    '[.e[] | select(.event == "tool_result") | .data.tool] == [.t.steps[]?.calls[]?.tool]'

  chat "$(session_id plain "$label")" "$msg"
  show_exchange "$msg (the same message through /chat)"
  assert "the same order as through /chat: ticker, quantity, price, total, cashAfter, status" json_true \
    "$(jq -nc --argjson a "$response" --argjson b "$RESP" '{a: $a, b: $b}')" \
    '[.a, .b] | map(.pendingOrder | {ticker, quantity, price, fee, total, cashAfter, status}) | .[0] == .[1]'

  first_ms="$(jq '.[0].ms' <<<"$events")"
  last_ms="$(jq '.[-1].ms' <<<"$events")"
  log "First event after $(awk -v x="$first_ms" 'BEGIN { printf "%.1f", x / 1000 }') s, the response after $(awk -v x="$last_ms" 'BEGIN { printf "%.1f", x / 1000 }') s; /chat answered in $(awk -v x="$CHAT_MS" 'BEGIN { printf "%.1f", x / 1000 }') s."
  assert "the first event comes before the response" test "$first_ms" -lt "$last_ms"
  printf '| %s | %s | %s | %s | %s |\n' "$(provider_model "$provider")" \
    "$(awk -v x="$first_ms" 'BEGIN { printf "%.1f", x / 1000 }')" "$(awk -v x="$last_ms" 'BEGIN { printf "%.1f", x / 1000 }')" \
    "$(awk -v x="$CHAT_MS" 'BEGIN { printf "%.1f", x / 1000 }')" \
    "$(jq -r '[.[] | select(.event == "tool_result") | .data.tool + (if .data.error then "(error)" else "" end)] | join(" → ")' <<<"$events")" \
    >>"$STREAM_SUMMARY"

  log ""
  log "Two streams of one session at once (agent.session-busy-timeout=100ms):"
  session="$(session_id busy "$label")"
  stream_raw "$prefix-busy-1" "$session" "What is in my account?" &
  busy_pid=$!
  sleep 0.5
  stream_raw "$prefix-busy-2" "$session" "What is in my account?"
  wait "$busy_pid" || true
  stream_parse "$prefix-busy-2"
  show_stream
  assert "the second stream is HTTP 200 with one error event: ProblemDetail 409" json_true "$STREAM_EVENTS" \
    'length == 1 and .[0].event == "error" and .[0].data.status == 409'
  stream_parse "$prefix-busy-1"
  assert "the first stream ends with its response" json_true "$STREAM_EVENTS" '.[-1].event == "response"'

  log ""
  log "Before the stream starts:"
  saved_account="$ACCOUNT"; ACCOUNT=NO-SUCH-ACCOUNT
  stream_raw "$prefix-404" "$(session_id nf "$label")" "What is in my account?"
  ACCOUNT="$saved_account"
  stream_parse "$prefix-404"
  assert "an unknown account → HTTP 404, not a stream (got $STREAM_STATUS)" test "$STREAM_STATUS" = 404
  stream_raw "$prefix-400" "" "What is in my account?"
  stream_parse "$prefix-400"
  assert "an empty sessionId → HTTP 400, not a stream (got $STREAM_STATUS)" test "$STREAM_STATUS" = 400
  stop_app
done

section "Streaming: time to the first event"
log "| Model | First event, s | Response, s | /chat, s | Streamed tools |"
log "|---|---|---|---|---|"
tee -a "$REPORT" <"$STREAM_SUMMARY"

finish
