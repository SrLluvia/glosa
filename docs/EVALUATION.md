# What the evaluation found

Numbers from `eval/run_eval.py --compare`, run against the real stack with
`nomic-embed-text` for embeddings and `llama3.2:3b` for answers.

They are reported because they are useful, not because they are flattering.
Three of the findings below contradict something I believed when I wrote the
code.

## Retrieval

48 questions over three short documents: 20 phrased in the documents' own words,
15 deliberately paraphrased, 8 asking for exact identifiers, 5 that the corpus
does not answer at all.

| Mode | recall@1 | recall@3 | recall@5 | MRR | paraphrased@5 |
|---|---|---|---|---|---|
| Keyword | 0.81 | 0.81 | 0.81 | 0.81 | 0.47 |
| Semantic | **0.93** | 0.98 | 1.00 | **0.96** | 1.00 |
| Hybrid | 0.91 | 0.98 | 1.00 | 0.95 | 1.00 |

**Hybrid does not beat semantic here, and saying otherwise would be a lie.** At
rank 1 it is slightly worse. By rank 3 they are level, and since five passages
reach the model, the two are equivalent for answering; the difference only shows
in the order of a search listing.

Why it is probably not generalisable either way: nine passages is a tiny corpus,
and semantic search over nine passages is close to unbeatable by construction.
Hybrid's advantage is supposed to appear on exact tokens in a large index, which
this cannot demonstrate.

### Two things the measurement caught

**Keyword search was broken, and nothing else would have shown it.** It scored
0.17, and 0.00 on paraphrased questions. `websearch_to_tsquery` joins terms with
AND, so "how many days of paid leave does everyone get" matched no passage,
because none contains all those words. The keyword half contributed nothing and
hybrid search was semantic search under another name — while answering perfectly
well, which is exactly why nobody would have noticed. Matching any term instead
of every term took it from 0.17 to 0.81.

**The fusion weight was not the lever it appeared to be.** Dropping the keyword
half's weight from 0.5 to 0.3 changed nothing at all. With the customary
constant of 60, the gap between adjacent ranks is about 0.0003 while merely
appearing in the other list is worth about 0.005 — so being in both lists beats
being first in the better one, almost regardless of weight. The constant was the
lever, not the weight.

## Grounding

| Metric | Original | Combined prompt | Separate gate |
|---|---|---|---|
| Citation rate | 1.00 | 1.00 | 1.00 |
| Phrase accuracy | 0.51 | 0.40 | 0.40 |
| Refusal correctness | **0.00** | 0.20 | **1.00** |

Every unanswerable question is now refused. Phrase accuracy did not come back
with it, which means the gate is also saying no to questions the passages do
answer — roughly five of the forty-three. That is a real trade, not a free win:
the system went from answering everything to declining a little too readily.

Which way to take that depends on what the thing is for. Documentation search
that occasionally says "not covered" when the answer was there is recoverable —
the person rephrases. One that confidently cites the holiday policy at a
question about pensions is not, because nothing signals that it was wrong. So
the trade is the right way round, and the honest next step is a larger chat
model rather than further prompt work, since a 3B model is being asked for a
judgement near the edge of what it can make.

**Refusal correctness started at zero.** Every question the documents did not
cover was answered anyway, confidently, citing passages about something else.
That is the failure this kind of system exists to avoid, and the only reason it
was found is that five unanswerable questions are in the dataset.

The cause was structural rather than a weak model: vector search returns its
nearest neighbours however far away they are, so the generator was never handed
an empty result and never had the chance to decline.

**The first fix made things worse in a way worth recording.** Instructing the
model to decide and then answer in one pass raised refusal to 0.20 and dropped
phrase accuracy from 0.51 to 0.40: it had become hesitant about questions the
passages plainly did answer. Judging and writing are different jobs, and a 3B
model asked to interleave them does both badly.

Splitting them into two calls — a yes-or-no relevance decision, then the answer
— is what the code does now, and it took refusal correctness to 1.00.

A distance threshold helps but cannot do it alone. The distributions overlap:
the furthest passage that genuinely answered its question sat at 0.507, the
nearest that did not at 0.442. The threshold is set at 0.52, above everything
that did answer, so it costs nothing and catches what it can.

## What these numbers are not

A small, hand-written dataset over three short documents. Large enough to catch
a regression and to have caught three real defects; far too small to support a
claim about retrieval quality in general. The figures are a ratchet against
making things worse, not evidence that this is good.
