"""Entry point: runs the evaluation and prints the table."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from dataset import QUESTIONS
from harness import AnswerOutcome, EvaluationClient, Report, RetrievalOutcome
from metrics import score_answers, score_retrieval

RESULTS_PATH = Path(__file__).parent / "results.json"

MODES = ("KEYWORD", "SEMANTIC", "HYBRID")


def run(skip_answers: bool, compare: bool) -> tuple[list[Report], Report]:
    """Measures retrieval, and optionally generation.

    The corpus is ingested once and every mode is measured against that same
    ingestion, so a difference between modes cannot be an artefact of chunking
    having run twice.
    """
    retrieval_reports: list[Report] = []
    answer_report = Report(mode="HYBRID")

    with EvaluationClient() as client:
        print(f"Corpus ingested into collection {client.collection_id}\n")

        for mode in (MODES if compare else ("HYBRID",)):
            report = Report(mode=mode)
            print(f"  retrieval: {mode}")
            for question in QUESTIONS:
                passages = client.search(question.text, limit=5, mode=mode)
                report.retrieval.append(
                    RetrievalOutcome(question, [p.get("heading") or "" for p in passages])
                )
            retrieval_reports.append(report)

        if not skip_answers:
            print("\n  generation (the slow half on a local model)")
            for index, question in enumerate(QUESTIONS, start=1):
                answer, citations = client.ask(question.text)
                answer_report.answers.append(AnswerOutcome(question, answer, citations))
                print(f"    [{index:>2}/{len(QUESTIONS)}] {question.text[:52]}")

    return retrieval_reports, answer_report


def main() -> int:
    parser = argparse.ArgumentParser(description="Measure retrieval and grounding")
    parser.add_argument(
        "--retrieval-only",
        action="store_true",
        help="Skip generation, which is much the slower half on a local model",
    )
    parser.add_argument(
        "--compare",
        action="store_true",
        help="Measure keyword, semantic and hybrid against the same corpus",
    )
    args = parser.parse_args()

    print("Running evaluation against http://localhost:8080\n")
    retrieval_reports, answer_report = run(args.retrieval_only, args.compare)

    print("\nRetrieval")
    header = (
        f"  {'mode':<10} {'recall@1':>9} {'recall@3':>9} "
        f"{'recall@5':>9} {'MRR':>6} {'paraphrased@5':>14}"
    )
    print(header)
    print("  " + "-" * (len(header) - 2))

    retrieval_payload: dict[str, object] = {}
    for report in retrieval_reports:
        scored = score_retrieval(report.retrieval)
        print(
            f"  {report.mode:<10} {scored.recall_at_1:>9.2f} {scored.recall_at_3:>9.2f} "
            f"{scored.recall_at_5:>9.2f} {scored.mrr:>6.2f} "
            f"{scored.paraphrased_recall_at_5:>14.2f}"
        )
        retrieval_payload[report.mode] = scored.__dict__

    payload: dict[str, object] = {"retrieval": retrieval_payload}

    if answer_report.answers:
        answers = score_answers(answer_report.answers)
        print("\nGrounding")
        print(f"  answers                {answers.answered}")
        print(f"  citation rate          {answers.citation_rate:.2f}")
        print(f"  phrase accuracy        {answers.phrase_accuracy:.2f}")
        print(f"  refusal correctness    {answers.refusal_correctness:.2f}")
        payload["answers"] = answers.__dict__

    RESULTS_PATH.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"\nWritten to {RESULTS_PATH.name}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
