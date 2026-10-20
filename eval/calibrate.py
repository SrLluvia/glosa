"""Finds the distance beyond which a passage is not worth answering from.

Vector search always returns the nearest passages, however far away they are.
"Nearest" is not "relevant", which is why the system answered every question
including the ones its documents do not cover. This measures where the two
populations actually separate, rather than guessing a threshold.
"""

from dataset import QUESTIONS
from harness import EvaluationClient


def main() -> None:
    answerable: list[float] = []
    unanswerable: list[float] = []

    with EvaluationClient() as client:
        for question in QUESTIONS:
            passages = client.search(question.text, limit=1, mode="SEMANTIC")
            if not passages:
                continue
            distance = passages[0].get("semanticDistance")
            if distance is None:
                continue
            (answerable if question.is_answerable else unanswerable).append(distance)

    answerable.sort()
    unanswerable.sort()

    print(f"answerable   n={len(answerable):>2}  "
          f"min={answerable[0]:.3f}  median={answerable[len(answerable)//2]:.3f}  "
          f"max={answerable[-1]:.3f}")
    print(f"unanswerable n={len(unanswerable):>2}  "
          f"min={unanswerable[0]:.3f}  median={unanswerable[len(unanswerable)//2]:.3f}  "
          f"max={unanswerable[-1]:.3f}")
    print(f"\nworst answerable:  {answerable[-3:]}")
    print(f"best unanswerable: {unanswerable[:3]}")

    if answerable[-1] < unanswerable[0]:
        print(f"\nThe two separate cleanly between {answerable[-1]:.3f} and {unanswerable[0]:.3f}.")
    else:
        print("\nThey overlap; any threshold trades a refusal against a missed answer.")


if __name__ == "__main__":
    main()
