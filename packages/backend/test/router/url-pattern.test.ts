import { describe, expect, test } from "bun:test";

import { FallbackURLPattern } from "../../src/router/url-pattern.ts";

/**
 * The production URLPattern fallback previously had zero executed coverage:
 * every test harness installed its own polyfill on globalThis first. The
 * harness copies are gone — the router now runs this implementation under
 * test — and this suite pins its contract directly.
 */
describe("FallbackURLPattern", () => {
  test("matches static segments exactly", () => {
    const pattern = new FallbackURLPattern({ pathname: "/worlds" });
    expect(pattern.exec("https://backend.example/worlds")).toEqual({ pathname: { groups: {} } });
    expect(pattern.exec("https://backend.example/worlds/extra")).toBeNull();
    expect(pattern.exec("https://backend.example/world")).toBeNull();
  });

  test("captures named params per segment", () => {
    const pattern = new FallbackURLPattern({ pathname: "/worlds/:worldId/session/enter" });
    const match = pattern.exec("https://backend.example/worlds/w_123/session/enter");
    expect(match?.pathname.groups).toEqual({ worldId: "w_123" });
    expect(pattern.exec("https://backend.example/worlds/w_123/session/leave")).toBeNull();
  });

  test("a named param never spans segments", () => {
    const pattern = new FallbackURLPattern({ pathname: "/worlds/:worldId" });
    expect(pattern.exec("https://backend.example/worlds/a/b")).toBeNull();
  });

  test("a trailing wildcard param captures the rest of the path", () => {
    const pattern = new FallbackURLPattern({ pathname: "/worlds/:worldId/blobs/:storageKey*" });
    const match = pattern.exec("https://backend.example/worlds/w1/blobs/blobs/ab/cdef.bin");
    expect(match?.pathname.groups).toEqual({ worldId: "w1", storageKey: "blobs/ab/cdef.bin" });
  });

  test("regex metacharacters in static segments are literal", () => {
    const pattern = new FallbackURLPattern({ pathname: "/v1.0/ping" });
    expect(pattern.exec("https://backend.example/v1.0/ping")).not.toBeNull();
    expect(pattern.exec("https://backend.example/v1x0/ping")).toBeNull();
  });

  test("query strings do not affect matching", () => {
    const pattern = new FallbackURLPattern({ pathname: "/storage/google/callback" });
    expect(pattern.exec("https://backend.example/storage/google/callback?code=x&state=y")).not.toBeNull();
  });
});
