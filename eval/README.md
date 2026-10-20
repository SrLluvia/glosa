# Evaluation

Retrieval quality is a number, not an opinion. This measures it.

Without something like this, "we added hybrid search and it is better" is a
claim nobody can check, including the person making it. The point is not the
absolute figures — on a corpus this small they mean little — but that a change
to chunking, to the fusion constant or to the embedding model can be shown to
have helped or hurt before it is merged.

## What is measured

**Retrieval** — does the right passage come back at all, and how high?

| Metric | Question it answers |
|---|---|
| `recall@k` | Was the correct passage anywhere in the top `k`? |
| `mrr` | How far down was it? First place scores 1, fifth scores 0.2 |

Retrieval is measured separately from generation on purpose. A model cannot
answer from a passage it was never given, so a bad answer has two possible
causes and conflating them makes both invisible.

**Grounding** — is the answer actually supported by what was retrieved?

| Metric | Question it answers |
|---|---|
| `citation_rate` | Do the answers cite their sources at all? |
| `refusal_correctness` | Does it say "not covered" exactly when it should? |

The refusal cases matter most. A system that answers everything confidently is
indistinguishable from one that works, right up until someone checks.

## Running it

Needs the stack up and the models pulled:

```bash
docker compose up -d
```

```bash
cd eval && python -m pytest -q
```

Or for the full report:

```bash
cd eval && python run_eval.py
```

The suite writes `results.json` and prints a table.

## Comparing configurations

`run_eval.py --mode keyword|semantic|hybrid` runs retrieval with one half, the
other, or both. That is how the claim "hybrid beats either half" was arrived at
rather than assumed, and how it stays honest when the chunker changes.

## Why the dataset is small

Forty questions, written by hand against two documents. Large enough to show a
regression, small enough that every question can be checked by a person — which
matters, because a generated dataset measures agreement with whatever generated
it rather than correctness.
