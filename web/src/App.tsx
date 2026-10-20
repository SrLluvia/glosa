import { useCallback, useEffect, useState } from "react";
import { api, ApiError, type Collection, type GlosaDocument, type Session } from "./api";
import { SignIn } from "./SignIn";
import { Documents } from "./Documents";
import { Ask } from "./Ask";

/** How often a document that is still being ingested is re-checked. */
const POLL_INTERVAL_MS = 2000;

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [collections, setCollections] = useState<Collection[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [documents, setDocuments] = useState<GlosaDocument[]>([]);
  const [error, setError] = useState<string | null>(null);

  const loadCollections = useCallback(async (active: Session) => {
    try {
      const { items } = await api.collections(active.token);
      setCollections(items);
      setSelected((current) => current ?? items[0]?.id ?? null);
    } catch (cause) {
      setError(describe(cause));
    }
  }, []);

  useEffect(() => {
    if (session) void loadCollections(session);
  }, [session, loadCollections]);

  const loadDocuments = useCallback(async () => {
    if (!session || !selected) return;
    try {
      const { items } = await api.documents(session.token, selected);
      setDocuments(items);
    } catch (cause) {
      setError(describe(cause));
    }
  }, [session, selected]);

  useEffect(() => {
    void loadDocuments();
  }, [loadDocuments]);

  // Ingestion happens in the background, so the list is re-read while anything
  // is still in flight and left alone once everything has settled.
  useEffect(() => {
    const working = documents.some((d) => d.status !== "READY" && d.status !== "FAILED");
    if (!working) return;
    const timer = setInterval(() => void loadDocuments(), POLL_INTERVAL_MS);
    return () => clearInterval(timer);
  }, [documents, loadDocuments]);

  if (!session) {
    return <SignIn onSignedIn={setSession} />;
  }

  const collection = collections.find((c) => c.id === selected) ?? null;

  return (
    <div className="app">
      <header>
        <div>
          <h1>Glosa</h1>
          <p className="muted">
            {session.email} · {session.tenantSlug}
          </p>
        </div>
        <button className="ghost" onClick={() => setSession(null)}>
          Sign out
        </button>
      </header>

      {error && (
        <p className="error" onClick={() => setError(null)}>
          {error}
        </p>
      )}

      <nav className="collections">
        {collections.map((item) => (
          <button
            key={item.id}
            className={item.id === selected ? "chip selected" : "chip"}
            onClick={() => setSelected(item.id)}
          >
            {item.name}
          </button>
        ))}
        <NewCollection
          onCreate={async (name) => {
            if (!session) return;
            try {
              const created = await api.createCollection(session.token, name);
              setCollections((current) => [...current, created]);
              setSelected(created.id);
            } catch (cause) {
              setError(describe(cause));
            }
          }}
        />
      </nav>

      {collection ? (
        <main>
          <Documents
            session={session}
            collection={collection}
            documents={documents}
            onChanged={loadDocuments}
            onError={setError}
          />
          <Ask session={session} collection={collection} />
        </main>
      ) : (
        <p className="muted empty">Create a collection to begin.</p>
      )}
    </div>
  );
}

function NewCollection({ onCreate }: { onCreate: (name: string) => Promise<void> }) {
  const [name, setName] = useState("");

  return (
    <form
      className="new-collection"
      onSubmit={async (event) => {
        event.preventDefault();
        if (!name.trim()) return;
        await onCreate(name.trim());
        setName("");
      }}
    >
      <input
        value={name}
        placeholder="New collection"
        onChange={(event) => setName(event.target.value)}
      />
      <button type="submit" disabled={!name.trim()}>
        Add
      </button>
    </form>
  );
}

function describe(cause: unknown): string {
  if (cause instanceof ApiError) {
    return cause.status === 403
      ? "Your role does not allow that."
      : cause.message;
  }
  return cause instanceof Error ? cause.message : "Something went wrong";
}
