#!/usr/bin/env bash
# Experiment 9. All 14 questions on Claude and Ollama: outcome, steps, tokens, cost, latency.
# Every model starts from a clean state; every question goes to a new session.
#
#   PROVIDERS="claude ollama"               which providers to run
#   OLLAMA_MODELS="qwen3.5:9b qwen2.5:14b"  several local models in a row (default OLLAMA_MODEL)
#   QUESTIONS="1 2 ... 14"                  a subset of the questions
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
. "$EXP_DIR/lib/questions.sh"
init_results 09-model-comparison "Experiment 9. Comparing the models on 14 questions"

QUESTIONS="${QUESTIONS:-1 2 3 4 5 6 7 8 9 10 11 12 13 14}"
build_app

labels=""
for provider in ${PROVIDERS:-claude ollama}; do
  if [ "$provider" = ollama ]; then models="${OLLAMA_MODELS:-$(provider_model ollama)}"; else models="$(provider_model "$provider")"; fi
  for model in $models; do
    label="$provider-$(printf '%s' "$model" | tr -c 'A-Za-z0-9.' '_')"
    section "$provider / $model"
    if [ "$provider" = ollama ]; then
      start_provider ollama OLLAMA_MODEL="$model" || continue
    else
      start_provider "$provider" || continue
    fi
    # shellcheck disable=SC2086
    run_questions "$label" $QUESTIONS
    stop_app
    labels="$labels $label"
  done
done

for label in $labels; do print_question_table "$label"; done

section "Question 12: who computed 53"
log "There is no \"maximum for an amount\" tool: if place_order got quantity=53, the model computed it"
log "(get_quote and a division in its text), and the code only checked that 53 × 185.25 ≤ 10,000.00."
log ""
jq -r --arg q "$(question_turns 12)" 'select(.message == $q)
  | "- \(.trace.model // "—"): tools: \([.trace.steps[]?.calls[]? | "\(.tool) \(.arguments)"] | join(" → ")); pendingOrder: \(.response.pendingOrder // {} | "\(.quantity // "—") × \(.price // "—") = \(.total // "—")")"' \
  "$RESULTS_DIR/chats.jsonl" 2>/dev/null | tee -a "$REPORT" || true

section "No money moved"
log "No question calls /confirm, so the account must stay as it was after the run."
log "A problem shows as EXECUTED on any order in chats.jsonl:"
executed="$(jq -r 'select(.response.pendingOrder.status? == "EXECUTED") | .message' "$RESULTS_DIR/chats.jsonl" 2>/dev/null | wc -l | tr -d ' ')"
assert "no orders executed without /confirm ($executed)" test "$executed" = 0

log ""
log "Raw exchanges (responses and traces): $RESULTS_DIR/chats.jsonl"
finish
