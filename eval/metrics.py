"""Turning outcomes into numbers.

Each metric is a few lines, and that is the point: a figure nobody can work out
by reading the code is a figure nobody will trust when it moves.
"""

from __future__ import annotations

from dataclasses import dataclass

from harness import AnswerOutcome, RetrievalOutcome


@dataclass(frozen=True)
class RetrievalMetrics:
    questions: int
    recall_at_1: float
    recall_at_3: float
    recall_at_5: float
    mrr: float
    paraphrased_recall_at_5: float
    """Recall restricted to questions phrased in words the document never uses.

    Reported separately because it is where the two halves of the search differ
    most, and an overall average hides exactly that.
    """


def score_retrieval(outcomes: list[RetrievalOutcome]) -> RetrievalMetrics:
    answerable = [outcome for outcome in outcomes if outcome.question.is_answerable]
    if not answerable:
        raise ValueError("No answerable questions to score")

    ranks = [outcome.rank_of_expected for outcome in answerable]

    paraphrased = [
        outcome for outcome in answerable if outcome.question.paraphrased
    ]

    return RetrievalMetrics(
        questions=len(answerable),
        recall_at_1=_recall(ranks, 1),
        recall_at_3=_recall(ranks, 3),
        recall_at_5=_recall(ranks, 5),
        mrr=_mean_reciprocal_rank(ranks),
        paraphrased_recall_at_5=_recall(
            [outcome.rank_of_expected for outcome in paraphrased], 5
        ) if paraphrased else 0.0,
    )


def _recall(ranks: list[int | None], k: int) -> float:
    """Share of questions whose answer appeared in the top k."""
    if not ranks:
        return 0.0
    found = sum(1 for rank in ranks if rank is not None and rank <= k)
    return found / len(ranks)


def _mean_reciprocal_rank(ranks: list[int | None]) -> float:
    """Average of 1/rank, so first place scores 1 and fifth scores 0.2.

    Rewards putting the right passage near the top rather than merely including
    it somewhere, which matters when only the first few reach the model.
    """
    if not ranks:
        return 0.0
    total = sum(0.0 if rank is None else 1.0 / rank for rank in ranks)
    return total / len(ranks)


@dataclass(frozen=True)
class AnswerMetrics:
    answered: int
    citation_rate: float
    """Share of answers to answerable questions that cite at least one source."""

    phrase_accuracy: float
    """Share whose text contains the figure or phrase the document gives.

    A blunt check, and deliberately so: it catches an answer that contradicts
    the source or invents a number, which is the failure that matters. Judging
    prose quality would need a model, and a model grading a model is a weaker
    signal than it looks.
    """

    refusal_correctness: float
    """Share of unanswerable questions that were correctly refused.

    The single most revealing number here. A system that answers everything
    confidently is indistinguishable from one that works, until it is checked.
    """


REFUSAL_MARKERS = ("do not cover", "does not cover", "not covered", "do not say",
                   "does not say", "no information", "not mentioned", "cannot answer",
                   "don't have", "do not contain", "does not contain")


def score_answers(outcomes: list[AnswerOutcome]) -> AnswerMetrics:
    answerable = [outcome for outcome in outcomes if outcome.question.is_answerable]
    unanswerable = [outcome for outcome in outcomes if not outcome.question.is_answerable]

    cited = sum(1 for outcome in answerable if outcome.citation_count > 0)

    correct_phrases = sum(
        1
        for outcome in answerable
        if outcome.question.expected_phrase
        and outcome.question.expected_phrase.lower() in outcome.answer.lower()
    )

    refused = sum(1 for outcome in unanswerable if _is_refusal(outcome.answer))

    return AnswerMetrics(
        answered=len(answerable),
        citation_rate=cited / len(answerable) if answerable else 0.0,
        phrase_accuracy=correct_phrases / len(answerable) if answerable else 0.0,
        refusal_correctness=refused / len(unanswerable) if unanswerable else 0.0,
    )


def _is_refusal(answer: str) -> bool:
    lowered = answer.lower()
    return any(marker in lowered for marker in REFUSAL_MARKERS)
