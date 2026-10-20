import { useState } from "react";
import { api, type Session } from "./api";

/** Sign-in, and registering an organisation, which are the same form plus a field. */
export function SignIn({ onSignedIn }: { onSignedIn: (session: Session) => void }) {
  const [registering, setRegistering] = useState(false);
  const [slug, setSlug] = useState("");
  const [organisation, setOrganisation] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);

    try {
      if (registering) {
        await api.register(slug, organisation || slug, email, password);
      }
      onSignedIn(await api.login(slug, email, password));
    } catch (cause) {
      // The server answers every rejection identically on purpose, so there is
      // nothing more specific to say here, and guessing would undo that.
      setError(cause instanceof Error ? cause.message : "Could not sign in");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="signin">
      <form onSubmit={submit}>
        <h1>Glosa</h1>
        <p className="muted">
          Ask questions of your own documents, and get answers that cite the page they came from.
        </p>

        <label>
          Organisation
          <input
            value={slug}
            onChange={(event) => setSlug(event.target.value.toLowerCase())}
            placeholder="acme"
            autoComplete="organization"
            required
          />
        </label>

        {registering && (
          <label>
            Display name
            <input
              value={organisation}
              onChange={(event) => setOrganisation(event.target.value)}
              placeholder="Acme Corp"
            />
          </label>
        )}

        <label>
          Email
          <input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            placeholder="you@acme.test"
            autoComplete="username"
            required
          />
        </label>

        <label>
          Password
          <input
            type="password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete={registering ? "new-password" : "current-password"}
            minLength={registering ? 12 : undefined}
            required
          />
          {registering && <small className="muted">At least 12 characters.</small>}
        </label>

        {error && <p className="error">{error}</p>}

        <button type="submit" disabled={busy}>
          {busy ? "Working…" : registering ? "Create organisation" : "Sign in"}
        </button>

        <button type="button" className="ghost" onClick={() => setRegistering(!registering)}>
          {registering ? "I already have an account" : "Create a new organisation"}
        </button>
      </form>
    </div>
  );
}
