# Experiment 8. Deterministic agent tests

**Task 8.** A scripted model (`ScriptedConversationFactory`) returns predefined tool calls; tests through `MockMvc`
and `@SpringBootTest`.

## Question

Can the whole agent behaviour (loop, tools, response DTO, errors) be checked without a real model, quickly and with
the same result on every run?

## Hypothesis

Yes: with `ToolConversationFactory` replaced by a script, the whole path `/chat → AssistantService → ToolLoop →
BrokerTools → BrokerService` is deterministic. Neither Ollama nor a Claude key is needed.

## The task's scenarios and their tests

| Scenario | Tests |
|---|---|
| Ambiguous search → `candidates` in the response | `ChatIntegrationTest#ambiguousNameReturnsCandidates`, `#afterCandidatesTheModelCannotGoOnWithAnotherToolAndMustAsk`, `AssistantServiceTest#candidatesComeFromFindSecurityResult`, `#laterExactMatchClearsCandidates` |
| Insufficient funds → refusal and no order | `ChatIntegrationTest#refusalIsAnOrdinaryAnswerWithoutPendingOrder`, `AssistantServiceTest#refusedOrderIsNotPending` |
| Looping model → step limit and an honest answer | `ChatIntegrationTest#stepLimitGivesHonestAnswer`, `AssistantServiceTest#limitReachedGivesHonestAnswer` |
| Failed tool → the loop does not crash | `ToolLoopTest#failedToolGivesTheModelAGenericErrorWithoutExceptionDetails`, `ChatIntegrationTest#failedMessageIsRolledBackAndItsOrderCancelled`, `AssistantServiceTest#errorDuringTheLoopAlsoRollsBackHistoryAndCancelsTheOrder`, `#providerFailureIsAssistantUnavailable` |

## Running

```bash
experiments/08-deterministic-agent-tests.sh
RUNS=10 experiments/08-deterministic-agent-tests.sh
```

## Steps

1. Four test groups, one per scenario of the task.
2. The whole test suite.
3. `RUNS` times in a row (default 3): `ChatIntegrationTest`, `ChatControllerTest`, `AssistantServiceTest`. The result
   is the same; the tests do not depend on order or on the shared Spring context (`DemoState` resets the data).

## Success criteria

Every group is green in every repeat; no network is needed anywhere.

## Results (2026-11-25)

A fake `ToolConversationFactory` and MockMvc tests of `/chat` give fast feedback before running on a real model.
All four scenarios are green, the whole suite is 360 tests, and the 56 agent tests passed three times in a row.

The run of experiment 10 found one flaky test, `aStreamedMessageThatFailsIsAnErrorEventAndIsRolledBack`: the test
helper `SseEvents` read the stream while an event was half written ("event:error", no data yet) and took it as
the final event. It now parses only blocks that end with the blank line; 8 runs out of 8 passed after the fix.
