export interface Env {
  DB?: D1Database;
  BLOBS?: R2Bucket;
  /** 0.3.0 realtime: per-world runtime coordinator Durable Objects. */
  WORLD_COORDINATOR?: DurableObjectNamespace;
  /** 0.3.0 realtime: per-player WebSocket gateway Durable Objects. */
  USER_GATEWAY?: DurableObjectNamespace;
  ACTIVE_STORAGE_PROVIDER?: "google-drive" | "r2";
  SESSION_TTL_HOURS?: string;
  PUBLIC_BASE_URL?: string;
  SIGNED_URL_TTL_SECONDS?: string;
  MOJANG_HAS_JOINED_ENDPOINT?: string;
  MOJANG_SERVICES_PUBLICKEYS_ENDPOINT?: string;
  /** Comma-separated base64 DER pins for the player-certificate key set (test hook / emergency lever). */
  MOJANG_PLAYER_CERTIFICATE_KEYS?: string;
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
  /**
   * Remote throttle levers: when set, responses carry suggested client
   * cadences (clients clamp and never go below their built-in defaults).
   * Unset = fields absent = clients use their defaults.
   */
  SUGGESTED_RUNTIME_POLL_INTERVAL_MS?: string;
  SUGGESTED_HOST_HEARTBEAT_INTERVAL_MS?: string;
  SUGGESTED_AUTOSAVE_INTERVAL_MS?: string;
  SUGGESTED_PRESENCE_INTERVAL_MS?: string;
  /** Growth valve: refuse new world creation at/above this count. Unset = unlimited. */
  MAX_ACTIVE_WORLDS?: string;
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
  batch(statements: D1PreparedStatement[]): Promise<Array<{ success: boolean; meta?: Record<string, unknown> }>>;
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
