#!/usr/bin/env bash
# Experiment 2. Provider through configuration: Claude ↔ Ollama is switched only with the LLM_PROVIDER variable.
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
init_results 02-provider-switch "Experiment 2. Switching the provider between Claude and Ollama"

section "Configuration tests"
run_tests "LlmConfig" 'LlmConfigTest'

if [ "${SKIP_LIVE:-0}" = 1 ]; then finish; exit; fi
build_app

section "One jar on different providers"
log "The code and application.yml do not change; runs differ only in LLM_PROVIDER."
log ""
for provider in ${PROVIDERS:-claude ollama}; do
  log "### $provider ($(provider_model "$provider"))"
  log ""
  start_provider "$provider" || continue
  assert "the startup log names provider $provider" grep -q "LLM provider: $provider/" "$APP_LOG"
  for pair in "What is in my account?|get_account_summary" "How much is TRNC?|get_quote"; do
    msg="${pair%|*}"; tool="${pair#*|}"
    chat "exp2-$provider-$RANDOM" "$msg"
    show_exchange "$msg"
    assert "answer 200 (got $CHAT_STATUS)" test "$CHAT_STATUS" = 200
    assert "the model in the trace is $provider" json_true "$TRACE" ".model | ascii_downcase | contains(\"$provider\")"
    assert "$tool was called" json_true "$TRACE" "[.steps[].calls[].tool] | index(\"$tool\") != null"
  done
  stop_app
done

section "Configuration errors stop the startup"
if start_app claude-no-key LLM_PROVIDER=claude ANTHROPIC_API_KEY=; then
  assert "claude without a key does not start" false
else
  log '```'; grep -m1 -o 'llm.provider=claude[^"]*' "$APP_LOG" | tee -a "$REPORT" || true; log '```'
  assert "claude without a key does not start, the reason is in the log" grep -q 'ANTHROPIC_API_KEY' "$APP_LOG"
fi
if start_app unknown-provider LLM_PROVIDER=openai; then
  assert "an unknown provider does not start" false
else
  assert "the unknown provider openai does not start, the reason is in the log" grep -qi "llm.provider" "$APP_LOG"
fi

finish
