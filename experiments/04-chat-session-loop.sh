#!/usr/bin/env bash
# Experiment 4. /chat, the session and the loop: multiple turns (question 8), session reset, the trace,
# the step limit with an honest answer, and the lock on parallel messages of one session.
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
. "$EXP_DIR/lib/questions.sh"
init_results 04-chat-session-loop "Experiment 4. /chat, the session, the loop and the trace"

PROVIDER="${PROVIDER:-${LLM_PROVIDER:-ollama}}"

section "Tests"
run_tests "AssistantService, sessions, history, traces, loop" \
  'AssistantServiceTest,SessionStoreTest,HistoryTest,TraceStoreTest,ToolLoopTest,TruncatedTurnTest'
run_tests "session and step limit over HTTP" \
  'ChatIntegrationTest#sessionKeepsHistoryAndResetStartsOver+sameSessionIdOnAnotherAccountIsAnotherConversation+stepLimitGivesHonestAnswer+traceIsAvailableForTheAnswer'

if [ "${SKIP_LIVE:-0}" = 1 ]; then finish; exit; fi
build_app

section "Multiple turns on $PROVIDER: question 8"
start_provider "$PROVIDER" || { FAILED=$((FAILED + 1)); finish; exit; }
run_questions "$PROVIDER" 8
print_question_table "$PROVIDER"

section "Session reset: without the history \"Logistics\" means nothing"
session="exp4-reset-$RANDOM"
chat "$session" "Buy 100 shares of Sample"
show_exchange "Buy 100 shares of Sample"
api DELETE "/chat/$session"
assert "DELETE /chat/$session → 204 (got $HTTP_STATUS)" test "$HTTP_STATUS" = 204
chat "$session" "Logistics"
show_exchange "Logistics"
assert "no order after the reset: the model does not remember \"Buy 100 shares of Sample\"" json_true "$RESP" '.pendingOrder == null'

section "A trace belongs to its account"
trace_id="$(jq -r '.traceId' <<<"$RESP")"
api GET "/traces/$trace_id"
assert "GET /traces/$trace_id with its own account → 200" test "$HTTP_STATUS" = 200
assert "the trace has the message, the model and steps with tokens and latency" json_true "$HTTP_BODY" \
  '.message == "Logistics" and (.model | length > 0) and (.steps | length > 0) and (.steps | all(has("inputTokens") and has("latencyMs") and has("cost")))'
saved_account="$ACCOUNT"; ACCOUNT=BRK-2
api GET "/traces/$trace_id"
ACCOUNT="$saved_account"
assert "the same trace with account BRK-2 → 404 (got $HTTP_STATUS)" test "$HTTP_STATUS" = 404
stop_app

section "Step limit and a busy session: agent.max-steps=1, agent.session-busy-timeout=100ms"
start_provider "$PROVIDER" AGENT_MAXSTEPS=1 AGENT_SESSIONBUSYTIMEOUT=100ms || { FAILED=$((FAILED + 1)); finish; exit; }

msg="Buy 100 shares of Sample Logistics"
chat "exp4-limit-$RANDOM" "$msg"
show_exchange "$msg"
assert "the task needs ≥ 2 steps (find_security, place_order), so limitReached = true" json_true "$RESP" '.limitReached == true'
assert "the answer is honest, not a cut-off text, and there is no order" json_true "$RESP" '.pendingOrder == null and (.answer | length > 0)'

log ""
log "Two parallel messages to one session:"
session="exp4-busy-$RANDOM"
body="$(jq -nc --arg s "$session" '{sessionId: $s, message: "What is in my account?"}')"
pids=""
for i in 1 2; do
  curl -s -o "$RESULTS_DIR/busy-$i.json" -w '%{http_code}' --max-time "$CHAT_TIMEOUT" "$BASE_URL/chat" \
    -H "X-Account-Id: $ACCOUNT" -H 'Content-Type: application/json' -d "$body" >"$RESULTS_DIR/busy-$i.code" &
  pids="$pids $!"
done
for pid in $pids; do wait "$pid" || true; done
codes="$(cat "$RESULTS_DIR/busy-1.code" "$RESULTS_DIR/busy-2.code" | sort | tr '\n' ' ')"
log "    response codes: $codes"
for i in 1 2; do log "    $i: $(cut -c1-200 "$RESULTS_DIR/busy-$i.json")"; done
assert "one message is processed, the other gets 409 \"session busy\"" test "$codes" = "200 409 "
stop_app

finish
