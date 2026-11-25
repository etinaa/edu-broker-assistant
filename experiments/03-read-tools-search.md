# Experiment 3. Read tools and ambiguous search

**Task 3.** `get_account_summary`, `find_security`, `get_quote`; questions 1–4, 13, 14 on both models.

## Question

On question 4 ("How many Training do I have?") does the model offer the options or pick one itself? Which layer
fixes guessing more reliably: the tool description, the system prompt or the code?

## Hypothesis

The tool description and the prompt are enough for a strong model (Claude) but not always for a local one. The
code works reliably: on `CANDIDATES`, `find_security` returns a `nextStep`, and `AmbiguityGuard` refuses other
tools until a search returns `EXACT`. Then the only thing the model can do is ask the user.

## Setup

- Every question in a new session, from the initial account.
- Question 4 is repeated `REPEAT_Q4` times (default 5) to measure stability, not one lucky answer.

## Running

```bash
experiments/03-read-tools-search.sh
PROVIDERS=ollama REPEAT_Q4=10 experiments/03-read-tools-search.sh
```

## Questions and checks

| # | Message | Automatic check |
|---|---|---|
| 1 | What is in my account? | `get_account_summary` called; the text has 10,000, TRNC, SMPB, DEMO |
| 2 | How many Sample Bank shares do I have? | the text has SMPB/Sample Bank and 100 |
| 3 | How many TRNC do I have? | the text has 20 |
| 4 | How many Training do I have? | `candidates` = TRNC, TRNP, TREN; the text mentions TRNP and TREN |
| 13 | US0000TRNP02 — how much is it? | the text has TRNP and 24.10 |
| 14 | What is the weather in Haifa? | no tool calls at all |

The text checks are rough (regular expressions); the full answers are in `report.md` and are worth reading.

## What to look at

- The table per model: outcome, steps, tools, tokens, cost, time.
- Question 4: how many of the `REPEAT_Q4` repeats ended with a question to the user, and which tools the model
  tried to call after `find_security` (`AmbiguityGuard` refuses them; the trace shows them as errors).

## Results (2026-11-25)

### Russian wording

- Before `AmbiguityGuard`, Ollama (qwen3.5:9b) got `CANDIDATES` on question 4 but picked TRNC itself (the response
  still had three `candidates`). Claude asked.
- After `nextStep` in the `find_security` result and `AmbiguityGuard` in the code: 5/5 — the model lists the
  options and asks.

### English wording

The guard did not help with the English question: Ollama never called `find_security`. The `get_account_summary`
description offered that tool for "how many X do I have", so the model read the summary, saw "Training Corp.
common stock" (TRNC is the only Training security in the account) and answered "20 TRNC". Without a search there
are no `CANDIDATES`, and `AmbiguityGuard` has nothing to enforce.

Fix in the tool descriptions (commit `3519032`): the summary example became "how many TRNC do I have", and both
`get_account_summary` and `find_security` say to call `find_security` first when the user names a security by
name rather than an exact ticker or ISIN.

| ollama/qwen3.5:9b, options offered | Before | After |
|---|---|---|
| How many Training do I have? | 0/5 | 5/5, then 10/10 in this script |
| Buy 10 Training | 5/5 | 5/5 |
| Sell 10 Training | — | 5/5 |

Claude offered the options in every repeat both before and after (5/5, then 10/10). Questions 1–4, 13, 14 after
the fix: 6/6 on both models; Claude 2.2 steps and $0.0042 per dialogue, ~2.0 s; Ollama 2.2 steps, ~12.8 s.

### Conclusion

Each layer covers a different failure. The code (`AmbiguityGuard`) stops the model from picking one candidate,
but only once it has searched. Whether it searches at all is decided by the tool descriptions. A description is
not a guarantee: if another model skips the search again, the next step is in code, an optional `query`
parameter of `get_account_summary` that goes through the same search.
