export interface Env {
  DB?: D1Database;
  BLOBS?: R2Bucket;
  ACTIVE_STORAGE_PROVIDER?: "google-drive" | "r2";
  SESSION_TTL_HOURS?: string;
  PUBLIC_BASE_URL?: string;
  SIGNED_URL_TTL_SECONDS?: string;
  MOJANG_HAS_JOINED_ENDPOINT?: string;
  SIGNING_SECRET?: string;
  ALLOW_DEV_AUTH?: string;
  ALLOW_DEV_INSECURE_E4MC?: string;
  DEV_AUTH_SECRET?: string;
  ALLOW_DEV_GOOGLE_OAUTH?: string;
  GOOGLE_OAUTH_CLIENT_ID?: string;
  GOOGLE_OAUTH_CLIENT_SECRET?: string;
  GOOGLE_OAUTH_REDIRECT_URI?: string;
  GOOGLE_OAUTH_SCOPES?: string;
  GOOGLE_DRIVE_API_BASE?: string;
  DEV_GOOGLE_EMAIL?: string;
  DRIVE_MAX_PARALLEL_DOWNLOADS?: string;
  DRIVE_MAX_UPLOAD_PREPARATIONS?: string;
  DRIVE_MAX_CONCURRENT_UPLOADS?: string;
  DRIVE_MAX_UPLOAD_STARTS_PER_SECOND?: string;
  DRIVE_RETRY_BASE_DELAY_MS?: string;
  DRIVE_RETRY_MAX_DELAY_MS?: string;
}

export interface D1ResultRow {
  [key: string]: unknown;
}

export interface D1PreparedStatement {
  bind(...values: unknown[]): D1PreparedStatement;
  first<T = D1ResultRow>(): Promise<T | null>;
  all<T = D1ResultRow>(): Promise<{ results: T[] }>;
  run(): Promise<{ success: boolean; meta?: Record<string, unknown> }>;
}

export interface D1Database {
  prepare(query: string): D1PreparedStatement;
}

export interface R2Bucket {
  head(key: string): Promise<R2Object | null>;
  get(key: string): Promise<R2ObjectBody | null>;
  delete(key: string): Promise<void>;
  put(
    key: string,
    value: ReadableStream | ArrayBuffer | ArrayBufferView | string | null,
    options?: { httpMetadata?: { contentType?: string } }
  ): Promise<void>;
}

export interface R2Object {
  key: string;
  size: number;
}

export interface R2ObjectBody extends R2Object {
  body: ReadableStream | null;
  arrayBuffer(): Promise<ArrayBuffer>;
  httpMetadata?: {
    contentType?: string;
  };
}
