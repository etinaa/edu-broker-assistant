#!/usr/bin/env bash
# Experiment 8. Deterministic agent tests: a scripted model replaces the provider; no Ollama and no Claude key.
. "$(cd "$(dirname "$0")" && pwd)/lib/common.sh"
init_results 08-deterministic-agent-tests "Experiment 8. Deterministic agent tests"

section "The four scenarios of the task"
run_tests "ambiguous search → candidates" \
  'ChatIntegrationTest#ambiguousNameReturnsCandidates+afterCandidatesTheModelCannotGoOnWithAnotherToolAndMustAsk,AssistantServiceTest#candidatesComeFromFindSecurityResult+laterExactMatchClearsCandidates'
run_tests "insufficient funds → refusal and no order" \
  'ChatIntegrationTest#refusalIsAnOrdinaryAnswerWithoutPendingOrder,AssistantServiceTest#refusedOrderIsNotPending'
run_tests "looping model → step limit and an honest answer" \
  'ChatIntegrationTest#stepLimitGivesHonestAnswer,AssistantServiceTest#limitReachedGivesHonestAnswer'
run_tests "failed tool → the loop does not crash" \
  'ToolLoopTest#failedToolGivesTheModelAGenericErrorWithoutExceptionDetails,ChatIntegrationTest#failedMessageIsRolledBackAndItsOrderCancelled,AssistantServiceTest#errorDuringTheLoopAlsoRollsBackHistoryAndCancelsTheOrder+providerFailureIsAssistantUnavailable'

section "The whole test suite"
run_tests "all tests" '*Test,*Tests'

section "Repeatability"
RUNS="${RUNS:-3}"
log "The /chat tests run $RUNS times in a row: the scripted model gives the same result every time."
for i in $(seq 1 "$RUNS"); do
  run_tests "ChatIntegrationTest, run $i" 'ChatIntegrationTest,ChatControllerTest,AssistantServiceTest'
done

finish
