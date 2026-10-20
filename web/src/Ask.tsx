import { useState } from "react";
import type { Citation, Collection, Session } from "./api";
import { useAsk } from "./useAsk";

export function Ask({ session, collection }: { session: Session; collection: Collection }) {
  const [question, setQuestion] = useState("");
  const { state, ask } = useAsk(session.token);
  const [openCitation, setOpenCitation] = useState<number | null>(null);

  return (
    <section className="ask">
      <h2>Ask</h2>

      <form
        onSubmit={(event) => {
          event.preventDefault();
          if (question.trim()) void ask(collection.id, question.trim());
        }}
      >
        <input
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          placeholder="How much holiday leave do we get?"
          disabled={state.status === "streaming"}
        />
        <button type="submit" disabled={state.status === "streaming" || !question.trim()}>
          {state.status === "streaming" ? "Thinking…" : "Ask"}
        </button>
      </form>

      {state.status !== "idle" && (
        <article>
          <p className="question">{state.question}</p>

          {/* Sources are rendered before the prose, and arrive first, so the
              answer can be read against its evidence rather than after it. */}
          {state.citations.length > 0 && (
            <ol className="citations">
              {state.citations.map((citation) => (
                <li key={citation.chunkId}>
                  <button
                    className="citation"
                    onClick={() =>
                      setOpenCitation(openCitation === citation.number ? null : citation.number)
                    }
                  >
                    [{citation.number}] {describeSource(citation)}
                  </button>
                  {openCitation === citation.number && (
                    <blockquote>{citation.content}</blockquote>
                  )}
                </li>
              ))}
            </ol>
          )}

          <p className="answer">
            {state.answer}
            {state.status === "streaming" && <span className="cursor" />}
          </p>

          {state.error && <p className="error">{state.error}</p>}
        </article>
      )}
    </section>
  );
}

/** Whatever the format can actually prove: a page for PDF, a heading for Markdown. */
function describeSource(citation: Citation): string {
  if (citation.pageNumber !== null) {
    return `${citation.filename}, page ${citation.pageNumber}`;
  }
  if (citation.heading) {
    return `${citation.filename} · ${citation.heading}`;
  }
  return citation.filename;
}
