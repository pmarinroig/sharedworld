import type { ApiErrorShape } from "../../shared/src/index.ts";

export class HttpError extends Error {
  status: number;
  code: string;
  /** When set, errorResponse emits a Retry-After header with this value. */
  retryAfterSeconds?: number;
  /**
   * HTTP status an upstream dependency (e.g. Mojang, Google Drive) answered
   * with, when this error wraps an upstream failure. Never serialized to
   * clients; lets callers branch on the upstream cause (429 vs outage)
   * without parsing messages.
   */
  upstreamStatus?: number;
  /** Head of the upstream error body (bounded). Never serialized to clients. */
  upstreamBody?: string;

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

export interface ErrorLogContext {
  /** Contents of the x-sharedworld-version request header (0.2.2+ clients). */
  clientVersion?: string | null;
  /** "METHOD /path" of the failing request. */
  route?: string;
}

export function errorResponse(error: unknown, context: ErrorLogContext = {}): Response {
  if (error instanceof HttpError) {
    if (error.status >= 500) {
      console.warn("SharedWorld request failed", {
        code: error.code,
        status: error.status,
        message: error.message,
        route: context.route,
        clientVersion: context.clientVersion ?? null
      });
    } else if (error.code !== "not_found") {
      // 4xx used to be invisible in Workers Logs, which made every field
      // report start from zero. One line with the mod version answers "which
      // release is failing" immediately. not_found is excluded so bot route
      // scans don't spam the log.
      console.warn("SharedWorld request rejected", {
        code: error.code,
        status: error.status,
        route: context.route,
        clientVersion: context.clientVersion ?? null
      });
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
  console.error("SharedWorld unhandled error", error, {
    route: context.route,
    clientVersion: context.clientVersion ?? null
  });
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
