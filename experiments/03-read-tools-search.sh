#!/usr/bin/env bash
# Experiment 3. Read tools and search: questions 1–4, 13, 14 on both models,
# and separately how stable question 4 is (options instead of a guess).
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
. "$EXP_DIR/lib/questions.sh"
init_results 03-read-tools-search "Experiment 3. Read tools and ambiguous search"

REPEAT_Q4="${REPEAT_Q4:-5}"

section "Tool tests"
run_tests "read tools and AmbiguityGuard" \
  'BrokerToolsAccountSummaryTest,BrokerToolsFindSecurityTest,BrokerToolsGetQuoteTest,AmbiguityGuardTest'
run_tests "candidates in /chat" \
  'ChatIntegrationTest#ambiguousNameReturnsCandidates+afterCandidatesTheModelCannotGoOnWithAnotherToolAndMustAsk'

if [ "${SKIP_LIVE:-0}" = 1 ]; then finish; exit; fi
build_app

for provider in ${PROVIDERS:-claude ollama}; do
  section "Questions 1–4, 13, 14 on $provider ($(provider_model "$provider"))"
  start_provider "$provider" || continue
  run_questions "$provider" 1 2 3 4 13 14

  section "Question 4 on $provider, $REPEAT_Q4 repeats"
  : >"$RESULTS_DIR/q4-$provider.tsv"
  for i in $(seq 1 "$REPEAT_Q4"); do
    chat "q4-repeat-$provider-$i-$RANDOM" "$(question_turns 4)"
    if eval_check "$(question_check 4 1)"; then verdict=asked; else verdict=guessed; fi
    picked="$(jq -r '[.steps[]?.calls[]? | select(.tool != "find_security") | "\(.tool) \(.arguments)"] | join("; ")' <<<"$TRACE")"
    log "- repeat $i: **$verdict**; candidates: $(jq -r '[.candidates[]?.ticker] | join(",")' <<<"$RESP");" \
      "other tools after the search: ${picked:-none}; answer: $(jq -r '.answer // .detail // ""' <<<"$RESP" | tr '\n' ' ' | cut -c1-150)"
    printf '%s\n' "$verdict" >>"$RESULTS_DIR/q4-$provider.tsv"
  done
  asked="$(grep -c '^asked$' "$RESULTS_DIR/q4-$provider.tsv" || true)"
  log ""
  assert "$provider offers the options on question 4 in every repeat ($asked/$REPEAT_Q4)" test "$asked" = "$REPEAT_Q4"
  stop_app
done

for provider in ${PROVIDERS:-claude ollama}; do
  if [ -f "$RESULTS_DIR/table-$provider.md" ]; then print_question_table "$provider"; fi
done

finish
