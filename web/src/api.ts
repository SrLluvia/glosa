/** Everything the app knows about the server. */

export type Role = "ADMIN" | "EDITOR" | "VIEWER";

export interface Session {
  token: string;
  tenantSlug: string;
  email: string;
}

export interface Collection {
  id: string;
  name: string;
  createdAt: string;
}

export type DocumentStatus = "PENDING" | "PARSING" | "EMBEDDING" | "READY" | "FAILED";

export interface GlosaDocument {
  id: string;
  filename: string;
  contentType: string;
  byteSize: number;
  status: DocumentStatus;
  pageCount: number | null;
  failureReason: string | null;
  createdAt: string;
}

export interface Citation {
  number: number;
  chunkId: string;
  documentId: string;
  filename: string;
  pageNumber: number | null;
  heading: string | null;
  content: string;
}

/** Thrown with the server's own wording, which is written for the reader. */
export class ApiError extends Error {
  // Declared and assigned separately rather than as a parameter property,
  // which the project's TypeScript settings disallow because it cannot be
  // erased to plain JavaScript.
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.status = status;
  }
}

async function request<T>(path: string, token: string | null, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (token) headers.set("Authorization", `Bearer ${token}`);
  if (init.body && !(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(path, { ...init, headers });

  if (!response.ok) {
    // The API answers RFC 7807, so there is a sentence written for a person
    // rather than a status code to translate.
    const problem = await response.json().catch(() => null);
    throw new ApiError(problem?.detail ?? problem?.title ?? response.statusText, response.status);
  }

  return response.status === 204 ? (undefined as T) : response.json();
}

export const api = {
  register(slug: string, organisationName: string, email: string, password: string) {
    return request<{ tenantId: string }>("/v1/tenants", null, {
      method: "POST",
      body: JSON.stringify({ slug, organisationName, email, password }),
    });
  },

  async login(tenantSlug: string, email: string, password: string): Promise<Session> {
    const body = await request<{ accessToken: string }>("/v1/auth/login", null, {
      method: "POST",
      body: JSON.stringify({ tenantSlug, email, password }),
    });
    return { token: body.accessToken, tenantSlug, email };
  },

  collections(token: string) {
    return request<{ items: Collection[] }>("/v1/collections?size=100", token);
  },

  createCollection(token: string, name: string) {
    return request<Collection>("/v1/collections", token, {
      method: "POST",
      body: JSON.stringify({ name }),
    });
  },

  documents(token: string, collectionId: string) {
    return request<{ items: GlosaDocument[] }>(
      `/v1/collections/${collectionId}/documents?size=100`,
      token,
    );
  },

  upload(token: string, collectionId: string, file: File) {
    const form = new FormData();
    form.append("file", file);
    return request<GlosaDocument>(`/v1/collections/${collectionId}/documents`, token, {
      method: "POST",
      body: form,
    });
  },
};
