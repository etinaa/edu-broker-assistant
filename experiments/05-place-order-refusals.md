# Experiment 5. `place_order` and refusals

**Task 5.** The tool creates an order or returns a refusal with the reason and the numbers. A refusal is an ordinary
result (not `is_error`), and the model relays it.

## Question

Do the numbers in the model's text match the numbers the code computed (`pendingOrder`, a `place_order` refusal)?

## Hypothesis

The model mostly relays the numbers from the tool result correctly, but only the response structure guarantees
them: the code builds `pendingOrder`, not the model. If the text disagrees with the structure, the structure is
right.

## Setup

- Questions 5–10, each in a new session, on every available model.
- No question calls `/confirm`: cash and positions must stay as they were after the run.

## Running

```bash
experiments/05-place-order-refusals.sh
PROVIDERS=claude experiments/05-place-order-refusals.sh
```

## Questions and checks

| # | Message | Automatic check |
|---|---|---|
| 5 | Buy 10 shares of DEMO | `pendingOrder` DEMO 10, total 4100, cashAfter 5900, `PENDING_CONFIRMATION` |
| 6 | Buy 30 shares of DEMO | no order, the text has 2,300 |
| 7 | Sell 50 TRNC | no order, the text has 20 |
| 8 | Buy 100 shares of Sample → Logistics | first candidates SMPB/SMPL, then `pendingOrder` SMPL 100, total 1230 |
| 9 | Buy Tesla shares | no order, `place_order` not called |
| 10 | Buy 2.5 shares of DEMO | no order |

## Comparing the numbers

The script builds a table "model · message · code numbers · missing in the text". The code numbers are `price`,
`total`, `cashAfter` from `pendingOrder`, or `needed`, `available`, `shortfall` from a refusal. Spaces, no-break
spaces and commas are removed from the text before the search (4,100.00 → 4100.00).

## Success criteria

- Questions 5–10 are PASS.
- The account after the run is 10,000.00 USD, TRNC 20, SMPB 100, DEMO 5.
- The comparison table has no mismatches (or each one is explained: rounding, another format, a model error).

## Results

Done: the four tools in `BrokerTools` are implemented and tested. In the 2026-11-25 run (experiment 9, Russian
wording), questions 5–10 passed on both models; Claude refused question 10 itself, without `place_order`.

2026-11-25, English wording, after the tool description change:

| | Correct | Steps | Per dialogue | HTTP |
|---|---|---|---|---|
| claude-haiku-4-5 | 6/6 | 2.2 | $0.0045 | ~2.6 s |
| ollama/qwen3.5:9b | 6/6 | 3.0 | free | ~23.7 s |

- **Numbers:** 0 mismatches. Every number the code computed (price, total, cash after, needed, available,
  shortfall) appears in the model's text, on both models.
- **Question 7:** Ollama answered "only 20 held" from `get_account_summary` without calling `place_order`, so
  there is no code refusal in the trace to compare with. The outcome is right, but here the model applied the rule
  itself; the code would have refused the same sale.
- **Question 10:** Claude again asked about the fractional quantity without calling `place_order`; Ollama called it
  and got the code's `INVALID_QUANTITY` refusal.
