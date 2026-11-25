# Experiment 2. Provider through configuration

**Task 2.** Claude ↔ Ollama is switched without changing the code.

## Question

Is one `LLM_PROVIDER` variable (that is, `llm.provider` in `application.yml`) enough for the same jar to run on
another model, and does the application refuse to start with a wrong configuration?

## Hypothesis

`LlmConfig` picks the client bean by `llm.provider`. Both providers answer the same questions with the same tool
calls, and the model shows in the trace. Claude without a key and an unknown provider stop the startup with a
clear reason.

## Setup

- One jar; `application.yml` does not change.
- Claude: `ANTHROPIC_API_KEY` in the environment or `.env`, model `CLAUDE_MODEL` (default `claude-haiku-4-5`).
- Ollama: `ollama serve`, the `OLLAMA_MODEL` model pulled and supporting tools.
- An unavailable provider is skipped with a note in the report.

## Running

```bash
experiments/02-provider-switch.sh
PROVIDERS=ollama experiments/02-provider-switch.sh   # one provider only
```

## Steps

1. `LlmConfigTest`: client choice, case of the name, error without a key, error without a provider.
2. For each provider, start the jar with `LLM_PROVIDER=<provider>` and ask:
   - "What is in my account?" — `get_account_summary` expected;
   - "How much is TRNC?" — `get_quote` expected.
3. Check the `LLM provider: ...` line in the startup log and the `model` field of the trace.
4. Start with `LLM_PROVIDER=claude ANTHROPIC_API_KEY=` and with `LLM_PROVIDER=openai`: the application must exit.

## Success criteria

- Both providers answer 200 and call the expected tools; `trace.model` contains the provider name.
- Claude without a key: the startup fails, the log mentions `ANTHROPIC_API_KEY`.
- Unknown provider: the startup fails, the log mentions `llm.provider`.

## Results (2026-11-25)

Checked live with no code change, only `LLM_PROVIDER`: claude/claude-haiku-4-5 and ollama/qwen3.5:9b answered both
questions the same way, with the same tool calls; the model shows in the trace. Claude without a key and an
unknown provider do not start.

The script, before and after the tool description change: all checks passed both times.
