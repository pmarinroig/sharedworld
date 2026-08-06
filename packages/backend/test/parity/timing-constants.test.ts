import { describe, expect, test } from "bun:test";

import * as contracts from "../../../shared/src/contracts.ts";

import { readJavaSource } from "./support.ts";

/**
 * The protocol timing constants exist twice: as exported TS values in
 * shared/src/contracts.ts (the backend's source of truth) and as Java literals
 * in the mod. Nothing at build time links them, so this test does: every
 * mapped pair must agree, and any new *_MS constant on either side must be
 * added to the map or explicitly allowlisted as one-sided.
 */

type Mapping = {
  tsName: keyof typeof contracts;
  javaFile: string;
  javaConstant: string;
};

const MAPPED_CONSTANTS: Mapping[] = [
  { tsName: "HOST_HEARTBEAT_INTERVAL_MS", javaFile: "host/SharedWorldHostingManager.java", javaConstant: "HEARTBEAT_INTERVAL_MS" },
  { tsName: "AUTOSAVE_INTERVAL_MS", javaFile: "host/SharedWorldHostingManager.java", javaConstant: "AUTOSAVE_INTERVAL_MS" },
  // The mod waits HOST_CONFIRM_TIMEOUT_MS for the backend to echo host-live;
  // that deadline intentionally mirrors the backend's host lease timeout.
  { tsName: "HOST_LEASE_TIMEOUT_MS", javaFile: "host/SharedWorldHostingManager.java", javaConstant: "HOST_CONFIRM_TIMEOUT_MS" },
  { tsName: "PLAYER_PRESENCE_HEARTBEAT_INTERVAL_MS", javaFile: "SharedWorldPresenceManager.java", javaConstant: "HEARTBEAT_INTERVAL_MS" }
];

/** Java-side timing constants that deliberately have no shared-contract twin. */
const JAVA_ONLY_CONSTANTS = new Set([
  "host/SharedWorldHostingManager.java#HEARTBEAT_RETRY_INTERVAL_MS",
  "host/SharedWorldHostingManager.java#JOIN_TARGET_TIMEOUT_MS",
  // Local safety caps for server-suggested pacing (remote throttle levers);
  // the backend has no matching literal — env vars drive the suggestions.
  "host/SharedWorldHostingManager.java#MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS",
  "host/SharedWorldHostingManager.java#MAX_SUGGESTED_AUTOSAVE_INTERVAL_MS",
  "SharedWorldPresenceManager.java#MAX_SUGGESTED_HEARTBEAT_INTERVAL_MS"
]);

/** Contract constants enforced only by the backend, with no mod-side literal. */
const TS_ONLY_CONSTANTS = new Set<keyof typeof contracts>([
  "HANDOFF_WAITER_TIMEOUT_MS",
  "PLAYER_PRESENCE_TIMEOUT_MS",
  "INVITE_TTL_MS",
  "STORAGE_LINK_TTL_MS"
]);

/** Java files that hold protocol-relevant timing literals. */
const WATCHED_JAVA_FILES = ["host/SharedWorldHostingManager.java", "SharedWorldPresenceManager.java"];

function extractJavaMsConstants(source: string): Map<string, number> {
  const constants = new Map<string, number>();
  const pattern = /static final long ([A-Z_]+_MS) = ([0-9_L*\s]+);/g;
  for (const match of source.matchAll(pattern)) {
    constants.set(match[1], evaluateJavaLongExpression(match[2]));
  }
  return constants;
}

function evaluateJavaLongExpression(expression: string): number {
  const factors = expression.replace(/[_L\s]/g, "").split("*");
  return factors.reduce((product, factor) => product * Number.parseInt(factor, 10), 1);
}

describe("timing constant parity", () => {
  test("every mapped constant agrees between contracts.ts and the Java literal", () => {
    for (const mapping of MAPPED_CONSTANTS) {
      const javaConstants = extractJavaMsConstants(readJavaSource(mapping.javaFile));
      const javaValue = javaConstants.get(mapping.javaConstant);
      expect(javaValue, `${mapping.javaFile}#${mapping.javaConstant} not found — mapping is stale`).toBeDefined();
      expect(
        javaValue,
        `${String(mapping.tsName)} (${String(contracts[mapping.tsName])}) != ${mapping.javaFile}#${mapping.javaConstant} (${javaValue})`
      ).toBe(contracts[mapping.tsName]);
    }
  });

  test("no unmapped *_MS constant exists on either side", () => {
    const unmappedJava: string[] = [];
    for (const javaFile of WATCHED_JAVA_FILES) {
      for (const name of extractJavaMsConstants(readJavaSource(javaFile)).keys()) {
        const qualified = `${javaFile}#${name}`;
        const mapped = MAPPED_CONSTANTS.some((m) => m.javaFile === javaFile && m.javaConstant === name);
        if (!mapped && !JAVA_ONLY_CONSTANTS.has(qualified)) {
          unmappedJava.push(qualified);
        }
      }
    }
    expect(unmappedJava, "new Java timing constants must be mapped or allowlisted").toEqual([]);

    const unmappedTs = Object.keys(contracts).filter((name) => {
      if (!name.endsWith("_MS")) {
        return false;
      }
      const mapped = MAPPED_CONSTANTS.some((m) => m.tsName === name);
      return !mapped && !TS_ONLY_CONSTANTS.has(name as keyof typeof contracts);
    });
    expect(unmappedTs, "new contract timing constants must be mapped or allowlisted").toEqual([]);
  });
});
