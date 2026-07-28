import type { ApiErrorShape } from "../../shared/src/index.ts";

export class HttpError extends Error {
  status: number;
  code: string;
  /** When set, errorResponse emits a Retry-After header with this value. */
  retryAfterSeconds?: number;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function json(data: unknown, init: ResponseInit = {}): Response {
  const headers = new Headers(init.headers);
  headers.set("content-type", "application/json; charset=utf-8");
  return new Response(JSON.stringify(data), { ...init, headers });
}

export async function readJson<T>(request: Request): Promise<T> {
  try {
    return (await request.json()) as T;
  } catch {
    throw new HttpError(400, "invalid_json", "Request body must be valid JSON.");
  }
}

export function errorResponse(error: unknown): Response {
  if (error instanceof HttpError) {
    if (error.status >= 500) {
      console.warn("SharedWorld request failed", { code: error.code, status: error.status, message: error.message });
    }
    const payload: ApiErrorShape = {
      error: error.code,
      message: error.message,
      status: error.status
    };
    const headers = error.retryAfterSeconds === undefined ? undefined : { "retry-after": String(error.retryAfterSeconds) };
    return json(payload, { status: error.status, headers });
  }

  // Anything else is an unexpected internal failure: log the real error, but
  // never leak its message to the client.
  console.error("SharedWorld unhandled error", error);
  const payload: ApiErrorShape = {
    error: "internal_error",
    message: "Internal server error.",
    status: 500
  };
  return json(payload, { status: 500 });
}

export function ok(): Response {
  return new Response(null, { status: 204 });
}
