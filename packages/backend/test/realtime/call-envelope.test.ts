import { describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { decodeCallBody, encodeCallBody, isErrorEnvelope, rethrowEnvelope, toErrorEnvelope } from "../../src/realtime/service.ts";

/**
 * The Worker→DO call envelope is the one seam the Bun harness never
 * exercises (LocalRealtimeService calls the logic in-process), so it gets
 * its own tests. The Date round-trip here is exactly the bug the workerd
 * smoke caught: Date.toJSON runs before JSON.stringify replacers.
 */
describe("coordinator call envelope", () => {
  test("Date arguments survive the round trip as Dates", () => {
    const now = new Date("2026-01-03T00:00:00.000Z");
    const decoded = decodeCallBody(encodeCallBody("heartbeat", [{ runtimeEpoch: 1 }, now]));
    expect(decoded.method).toBe("heartbeat");
    expect(decoded.args[1]).toBeInstanceOf(Date);
    expect((decoded.args[1] as Date).toISOString()).toBe(now.toISOString());
  });

  test("nested Dates inside argument objects survive too", () => {
    const at = new Date("2026-01-03T00:00:30.000Z");
    const decoded = decodeCallBody(encodeCallBody("x", [{ deep: { at } }]));
    expect((decoded.args[0] as { deep: { at: Date } }).deep.at).toBeInstanceOf(Date);
  });

  test("plain ISO strings are NOT revived into Dates", () => {
    const decoded = decodeCallBody(encodeCallBody("x", ["2026-01-03T00:00:00.000Z"]));
    expect(typeof decoded.args[0]).toBe("string");
  });

  test("HttpError crosses as a typed envelope and rethrows faithfully", () => {
    const envelope = toErrorEnvelope(new HttpError(409, "host_not_active", "Nope."));
    expect(isErrorEnvelope(envelope)).toBe(true);
    try {
      rethrowEnvelope(envelope);
      throw new Error("expected rethrow");
    } catch (error) {
      expect(error).toBeInstanceOf(HttpError);
      expect((error as HttpError).status).toBe(409);
      expect((error as HttpError).code).toBe("host_not_active");
    }
  });

  test("non-HttpError failures are not swallowed into envelopes", () => {
    expect(() => toErrorEnvelope(new TypeError("boom"))).toThrow(TypeError);
  });
});
