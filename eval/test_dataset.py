"""Guards the evaluation itself.

A dataset can rot without anybody noticing: rename a heading in the corpus and
the question pointing at it simply stops being scored, which makes the numbers
go up for the wrong reason. These checks run in CI, where the full evaluation
cannot, because they need neither a stack nor a model.
"""

import unittest

from dataset import DOCUMENTS, QUESTIONS
from harness import RetrievalOutcome
from metrics import score_answers, score_retrieval
from harness import AnswerOutcome


def _headings() -> set[str]:
    return {
        line.lstrip("# ").strip()
        for document in DOCUMENTS.values()
        for line in document.splitlines()
        if line.startswith("#")
    }


class TestDataset(unittest.TestCase):
    def test_every_expected_heading_exists_in_the_corpus(self) -> None:
        headings = _headings()
        for question in QUESTIONS:
            if question.expected_heading is not None:
                self.assertIn(
                    question.expected_heading,
                    headings,
                    f"'{question.text}' points at a heading the corpus no longer has",
                )

    def test_every_expected_phrase_appears_in_the_corpus(self) -> None:
        # Whitespace is collapsed first: the documents are wrapped prose, so a
        # two-word phrase can straddle a line break and a literal check would
        # report it missing when it is plainly there.
        corpus = " ".join(" ".join(DOCUMENTS.values()).split()).lower()
        for question in QUESTIONS:
            if question.expected_phrase:
                self.assertIn(
                    question.expected_phrase.lower(),
                    corpus,
                    f"'{question.text}' expects wording the documents do not contain",
                )

    def test_unanswerable_questions_are_present(self) -> None:
        # The most revealing cases in the set. Losing them would hide the
        # failure mode the system most needs to be held to.
        unanswerable = [q for q in QUESTIONS if not q.is_answerable]
        self.assertGreaterEqual(len(unanswerable), 5)

    def test_paraphrased_questions_are_present(self) -> None:
        # Without these the comparison between retrieval modes is not a fair
        # one: keyword search would never be asked to do the thing it is bad at.
        paraphrased = [q for q in QUESTIONS if q.paraphrased]
        self.assertGreaterEqual(len(paraphrased), 10)


class TestMetrics(unittest.TestCase):
    def outcome_for(self, heading: str | None, retrieved: list[str]) -> RetrievalOutcome:
        question = next(q for q in QUESTIONS if q.expected_heading == heading)
        return RetrievalOutcome(question, retrieved)

    def test_recall_counts_a_hit_only_within_k(self) -> None:
        heading = QUESTIONS[0].expected_heading
        assert heading is not None
        at_four = self.outcome_for(heading, ["x", "y", "z", heading])

        scored = score_retrieval([at_four])

        self.assertEqual(scored.recall_at_1, 0.0)
        self.assertEqual(scored.recall_at_3, 0.0)
        self.assertEqual(scored.recall_at_5, 1.0)

    def test_reciprocal_rank_rewards_being_near_the_top(self) -> None:
        heading = QUESTIONS[0].expected_heading
        assert heading is not None

        first = score_retrieval([self.outcome_for(heading, [heading])]).mrr
        fourth = score_retrieval([self.outcome_for(heading, ["a", "b", "c", heading])]).mrr

        self.assertEqual(first, 1.0)
        self.assertAlmostEqual(fourth, 0.25)
        self.assertGreater(first, fourth)

    def test_a_refusal_counts_only_for_an_unanswerable_question(self) -> None:
        unanswerable = next(q for q in QUESTIONS if not q.is_answerable)
        answerable = next(q for q in QUESTIONS if q.is_answerable)

        scored = score_answers([
            AnswerOutcome(unanswerable, "The documents do not cover this.", 0),
            AnswerOutcome(answerable, "The documents do not cover this.", 1),
        ])

        # Refusing correctly and refusing when it should have answered are very
        # different, and collapsing them would hide the second.
        self.assertEqual(scored.refusal_correctness, 1.0)
        self.assertEqual(scored.phrase_accuracy, 0.0)


if __name__ == "__main__":
    unittest.main()
