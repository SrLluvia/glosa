import { useRef, useState } from "react";
import { api, type Collection, type GlosaDocument, type Session } from "./api";

const STATUS_LABEL: Record<GlosaDocument["status"], string> = {
  PENDING: "Queued",
  PARSING: "Reading",
  EMBEDDING: "Indexing",
  READY: "Ready",
  FAILED: "Failed",
};

export function Documents({
  session,
  collection,
  documents,
  onChanged,
  onError,
}: {
  session: Session;
  collection: Collection;
  documents: GlosaDocument[];
  onChanged: () => void;
  onError: (message: string) => void;
}) {
  const [uploading, setUploading] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);

  async function upload(file: File) {
    setUploading(true);
    try {
      await api.upload(session.token, collection.id, file);
      onChanged();
    } catch (cause) {
      onError(cause instanceof Error ? cause.message : "The upload failed");
    } finally {
      setUploading(false);
      if (fileInput.current) fileInput.current.value = "";
    }
  }

  return (
    <section className="documents">
      <h2>Documents</h2>

      <label className="upload">
        <input
          ref={fileInput}
          type="file"
          accept=".pdf,.md,.txt,application/pdf,text/markdown,text/plain"
          disabled={uploading}
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) void upload(file);
          }}
        />
        <span>{uploading ? "Uploading…" : "Add a PDF, Markdown or text file"}</span>
      </label>

      {documents.length === 0 ? (
        <p className="muted">Nothing here yet.</p>
      ) : (
        <ul>
          {documents.map((document) => (
            <li key={document.id}>
              <div>
                <span className="filename">{document.filename}</span>
                {document.pageCount !== null && (
                  <span className="muted"> · {document.pageCount} pages</span>
                )}
              </div>
              <span className={`status ${document.status.toLowerCase()}`}>
                {STATUS_LABEL[document.status]}
              </span>
              {/* Says why, rather than leaving a bare Failed for somebody to
                  guess at. A scanned PDF is the common case and the message
                  names OCR. */}
              {document.failureReason && (
                <p className="failure">{document.failureReason}</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
