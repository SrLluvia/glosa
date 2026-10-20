"""The evaluation corpus and the questions asked of it.

Written by hand. A generated dataset measures agreement with whatever generated
it, which is a different thing from correctness, and it removes the one property
that makes a small set useful: that a person can read every question and judge
whether the expected answer is right.

Each question names the heading of the passage that answers it. Matching on the
heading rather than on a chunk id keeps the dataset valid when the chunker
changes, which is exactly the kind of change this suite exists to measure.
"""

from __future__ import annotations

from dataclasses import dataclass

HANDBOOK = """\
# Holiday allowance

Everyone receives twenty-three days of paid leave each year. Days must be taken
before the end of December. They do not carry over, and any that remain unused
at the end of the year are lost.

# Expense claims

Expenses must be submitted before the fifth of the following month. Anything
arriving after that date is paid in the cycle after the next one, which usually
means a delay of about six weeks. Receipts are required for any amount above
twenty pounds.

# Remote work

Staff may work remotely up to three days a week. Working from outside the
country requires written approval from your manager in advance, because of tax
and insurance obligations. There is no limit on how long approved overseas work
may last.

# Probation

New joiners are on probation for six months. During probation either side may
end the contract with two weeks of notice instead of the usual two months.

# Equipment

Each person gets a laptop and one external monitor. Replacements are on a
three-year cycle. Anything beyond the standard set needs sign-off from a team
lead and comes out of the team budget.
"""

SECURITY_POLICY = """\
# Passwords

Passwords must be at least twelve characters. Reuse of any previous password is
not permitted. Multi-factor authentication is required on every account without
exception.

# Incident reporting

Report any suspected breach within one hour of noticing it. Contact the security
team directly and do not discuss the incident in open channels. The security
team decides what is disclosed and when.

# Data retention

Customer records are kept for seven years after the account closes. Logs are
kept for ninety days. Anything containing personal data must be deleted once its
retention period ends.

# Access reviews

Access to production systems is reviewed every quarter. Anyone who has not used
an account in sixty days has it suspended automatically.
"""

RUNBOOK = """# Error GL-4031

Returned when a tenant exceeds its storage quota. Raise the quota in the admin
console, or ask the customer to delete documents. Retrying without changing
anything will fail again.

# Error GL-5002

The embedding service timed out. Usually the model is cold after a restart.
Wait sixty seconds and retry; if it persists, check that Ollama has the model
installed.

# Upgrading to v2.14.3

Run the migration before deploying the new build, not after. Version v2.14.3
adds a NOT NULL column and a running v2.13 instance will fail to insert once it
is applied.

# SSO configuration

Single sign-on uses SAML 2.0. The assertion consumer service URL must end in
/saml/acs and the certificate must be PEM encoded. An RSA key shorter than 2048
bits is rejected.

# SLA targets

Priority one incidents carry a four hour response target. Priority two is one
business day. These targets apply to response, not resolution.
"""

DOCUMENTS: dict[str, str] = {
    "handbook.md": HANDBOOK,
    "security-policy.md": SECURITY_POLICY,
    "runbook.md": RUNBOOK,
}


@dataclass(frozen=True)
class Question:
    """One evaluation case.

    :param text: what is asked
    :param expected_heading: heading of the passage that answers it, or None
        when the corpus does not answer it at all
    :param expected_phrase: a distinctive string the answer should contain,
        checked literally; None when the right answer is a refusal
    :param paraphrased: True when the question deliberately avoids the wording
        of the document, which is where keyword search is expected to struggle
    """

    text: str
    expected_heading: str | None
    expected_phrase: str | None = None
    paraphrased: bool = False

    @property
    def is_answerable(self) -> bool:
        return self.expected_heading is not None


QUESTIONS: list[Question] = [
    # --- Direct: the question shares vocabulary with the document ---------
    Question("How many days of paid leave does everyone get?", "Holiday allowance", "twenty-three"),
    Question("Do unused holiday days carry over?", "Holiday allowance", "lost"),
    Question("When must expenses be submitted?", "Expense claims", "fifth"),
    Question("Are receipts required for expenses?", "Expense claims", "twenty"),
    Question("How many days a week can staff work remotely?", "Remote work", "three"),
    Question("What is needed to work from outside the country?", "Remote work", "approval"),
    Question("How long is probation?", "Probation", "six months"),
    Question("What notice applies during probation?", "Probation", "two weeks"),
    Question("What equipment does each person get?", "Equipment", "monitor"),
    Question("How often is equipment replaced?", "Equipment", "three-year"),
    Question("How long must passwords be?", "Passwords", "twelve"),
    Question("Is multi-factor authentication required?", "Passwords", "required"),
    Question("How quickly must a breach be reported?", "Incident reporting", "one hour"),
    Question("Who decides what is disclosed about an incident?", "Incident reporting", "security team"),
    Question("How long are customer records kept?", "Data retention", "seven years"),
    Question("How long are logs kept?", "Data retention", "ninety"),
    Question("How often is production access reviewed?", "Access reviews", "quarter"),
    Question("When is an unused account suspended?", "Access reviews", "sixty"),
    Question("Can I reuse an old password?", "Passwords", "not permitted"),
    Question("Who approves equipment beyond the standard set?", "Equipment", "team lead"),
    # --- Paraphrased: the answer is there, the words are not --------------
    # These are the cases keyword search alone is expected to miss, and the
    # reason the vector half exists.
    Question("What is the deadline for claiming money back?", "Expense claims", "fifth", paraphrased=True),
    Question("How much time off do I accrue annually?", "Holiday allowance", "twenty-three", paraphrased=True),
    Question("Can I do my job from abroad?", "Remote work", "approval", paraphrased=True),
    Question("What happens if I am let go in my first month?", "Probation", "two weeks", paraphrased=True),
    Question("How strong do my credentials need to be?", "Passwords", "twelve", paraphrased=True),
    Question("What is the turnaround for flagging a security problem?", "Incident reporting", "one hour", paraphrased=True),
    Question("How long do you hang on to client information?", "Data retention", "seven years", paraphrased=True),
    Question("What happens to a dormant login?", "Access reviews", "sixty", paraphrased=True),
    Question("Will I be given a second screen?", "Equipment", "monitor", paraphrased=True),
    Question("Is there a cap on days spent working overseas?", "Remote work", "no limit", paraphrased=True),
    Question("What is the trial period for someone who just joined?", "Probation", "six months", paraphrased=True),
    Question("Do I lose unused time off at year end?", "Holiday allowance", "lost", paraphrased=True),
    Question("Where should I not talk about a breach?", "Incident reporting", "open channels", paraphrased=True),
    Question("When do old records have to go?", "Data retention", "deleted", paraphrased=True),
    Question("Do I need proof for small purchases?", "Expense claims", "twenty", paraphrased=True),
    # --- Identifiers: exact tokens a vector model has no feel for ---------
    # The cases hybrid search exists for. An embedding of "GL-4031" carries
    # almost no meaning, while a keyword index matches it exactly. Without
    # questions like these the evaluation never tests what the keyword half is
    # actually good at, and the comparison between modes is not a fair one.
    Question("What does error GL-4031 mean?", "Error GL-4031", "quota"),
    Question("How do I fix GL-5002?", "Error GL-5002", "sixty seconds"),
    Question("What changed in v2.14.3?", "Upgrading to v2.14.3", "NOT NULL"),
    Question("Do I migrate before or after deploying v2.14.3?", "Upgrading to v2.14.3", "before"),
    Question("What SAML version does SSO use?", "SSO configuration", "2.0"),
    Question("What must the ACS URL end in?", "SSO configuration", "/saml/acs"),
    Question("What is the minimum RSA key size?", "SSO configuration", "2048"),
    Question("What is the P1 response target?", "SLA targets", "four hour"),

    # --- Unanswerable: the corpus does not cover these --------------------
    # The most important cases in the set. A system that answers these
    # confidently looks identical to one that works, until somebody checks.
    Question("What is the parental leave policy?", None),
    Question("How much is the annual bonus?", None),
    Question("Which pension provider is used?", None),
    Question("What is the dress code?", None),
    Question("How do I book a meeting room?", None),
]
