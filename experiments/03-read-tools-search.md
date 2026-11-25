# Experiment 3. Read tools and ambiguous search

**Task 3.** `get_account_summary`, `find_security`, `get_quote`; questions 1–4, 13, 14 on both models.

## Question

On question 4 ("How many Training do I have?") does the model offer the options or pick one itself? Which layer
fixes guessing more reliably: the tool description, the system prompt or the code?

## Hypothesis

The tool description and the prompt are enough for a strong model (Claude) but not always for a local one. The
code works reliably: on `CANDIDATES`, `find_security` returns a `nextStep`, and `AmbiguityGuard` refuses other
tools until a search returns `EXACT`. Then the only thing the model can do is ask the user. That `EXACT` unblocks
only the reading tools: `place_order` stays refused until the user's next message, because a narrower search the
model makes itself ("Training" → "TRNC") is still its own choice, not the user's.

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

## Results (2026-11-25, claude-haiku-4-5 and ollama/qwen3.5:9b)

| | Correct | Steps | Per dialogue | HTTP |
|---|---|---|---|---|
| claude-haiku-4-5 | 6/6 | 2.2 | $0.0043 | ~2.2 s |
| ollama/qwen3.5:9b | 6/6 | 2.0 | free | ~12.9 s |

Question 4 repeated 5 times per model: the options were offered 5/5 on both, with no tools other than `find_security`.

- **Question 4:** both models called `find_security`, got `CANDIDATES` and offered TRNC, TRNP and TREN.
- **Question 2:** Claude searched for "Sample Bank" and then read the account; Ollama read only the account. Both
  answered 100 SMPB.

### Conclusion

Each layer covers a different failure. The code (`AmbiguityGuard`) stops the model from picking one candidate,
but only once it has searched. Whether it searches at all is decided by the tool descriptions: before commit
`3519032` the `get_account_summary` description offered that tool for "how many X do I have", and the local model
answered "20 TRNC" on question 4 without a search. A description is not a guarantee: if another model skips the
search again, the next step is in code, an optional `query` parameter of `get_account_summary` that goes through
the same search.

The guard also keeps orders out of the message that got the candidates. An earlier version let a narrower `EXACT`
search unblock `place_order` too, so for "Buy 10 Training" the model could search for "TRNC" itself and place the
order without asking (the order still waited for `/confirm`). Now that search unblocks only the reading tools. The
results above are not affected: questions 1–4 place no orders.
