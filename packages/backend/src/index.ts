import type { Env } from "./env.ts";
import { createLaneDApp, isLaneD, type LaneDEnv } from "./lane-d.ts";

export interface SharedWorldApp {
  fetch(request: Request, executionContext?: { waitUntil(task: Promise<unknown>): void }): Promise<Response>;
  scheduled(now?: Date): Promise<number>;
}

/**
 * The worker is only the lane-D front for the Rust server: it forwards
 * legacy-client HTTP + WebSocket traffic to the box and relays blob
 * downloads from Google Drive (see lane-d.ts). The D1/DO backend it once
 * hosted was retired after the 2026-08-19 cutover (git history has it).
 */
export function createApp(env: Env): SharedWorldApp {
  if (!isLaneD(env as LaneDEnv)) {
    throw new Error("SharedWorld worker requires MODE=lane-d; the D1/DO backend was retired.");
  }
  const laneD = createLaneDApp(env as LaneDEnv);
  return { fetch: (request) => laneD.fetch(request), scheduled: () => laneD.scheduled() };
}

export default {
  fetch(request: Request, env: Env, ctx: { waitUntil(task: Promise<unknown>): void }) {
    return createApp(env).fetch(request, ctx);
  }
};
