import { useCallback, useRef, useState } from "react";
import type { Citation } from "./api";

export interface AskState {
  question: string;
  answer: string;
  citations: Citation[];
  status: "idle" | "streaming" | "done" | "error";
  error: string | null;
}

const IDLE: AskState = { question: "", answer: "", citations: [], status: "idle", error: null };

/**
 * Streams an answer from the server.
 *
 * <p>Uses fetch rather than EventSource, because EventSource cannot send an
 * Authorization header and the alternative is putting the token in the query
 * string, where it would end up in server logs and browser history.
 */
export function useAsk(token: string) {
  const [state, setState] = useState<AskState>(IDLE);
  const abortRef = useRef<AbortController | null>(null);

  const ask = useCallback(
    async (collectionId: string, question: string) => {
      // A previous answer still arriving would interleave with this one.
      abortRef.current?.abort();
      const controller = new AbortController();
      abortRef.current = controller;

      setState({ ...IDLE, question, status: "streaming" });

      try {
        const response = await fetch(
          `/v1/collections/${collectionId}/ask?q=${encodeURIComponent(question)}`,
          {
            headers: { Authorization: `Bearer ${token}`, Accept: "text/event-stream" },
            signal: controller.signal,
          },
        );

        if (!response.ok || !response.body) {
          const problem = await response.json().catch(() => null);
          throw new Error(problem?.detail ?? "The question could not be answered");
        }

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = "";

        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;

          buffer += decoder.decode(value, { stream: true });

          // Events are separated by a blank line; anything after the last one
          // is an event still arriving, so it stays in the buffer.
          const events = buffer.split("\n\n");
          buffer = events.pop() ?? "";

          for (const event of events) {
            applyEvent(event, setState);
          }
        }

        setState((current) =>
          current.status === "error" ? current : { ...current, status: "done" },
        );
      } catch (error) {
        if (controller.signal.aborted) return;
        setState((current) => ({
          ...current,
          status: "error",
          error: error instanceof Error ? error.message : "Something went wrong",
        }));
      }
    },
    [token],
  );

  return { state, ask };
}

function applyEvent(raw: string, setState: React.Dispatch<React.SetStateAction<AskState>>) {
  let name = "message";
  const dataLines: string[] = [];

  for (const line of raw.split("\n")) {
    if (line.startsWith("event:")) {
      name = line.slice(6).trim();
    } else if (line.startsWith("data:")) {
      // Only the single space the format inserts is removed. Stripping further
      // would weld the answer's words together, since tokens carry their own
      // leading spaces.
      const value = line.slice(5);
      dataLines.push(value.startsWith(" ") ? value.slice(1) : value);
    }
  }

  const data = dataLines.join("\n");

  if (name === "citations") {
    setState((current) => ({ ...current, citations: JSON.parse(data) as Citation[] }));
  } else if (name === "token") {
    setState((current) => ({ ...current, answer: current.answer + data }));
  } else if (name === "error") {
    setState((current) => ({ ...current, status: "error", error: data }));
  }
}
