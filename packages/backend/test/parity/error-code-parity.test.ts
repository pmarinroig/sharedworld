import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, test } from "bun:test";

import { FABRIC_MAIN_JAVA } from "./support.ts";

/**
 * The mod special-cases certain backend error codes (routing them to terminal
 * screens or retry policies) by comparing string literals against
 * SharedWorldApiClient.errorCode()/ApiError.error(). If the backend renames a
 * code, that classification silently stops matching. This test pins: every
 * code the mod classifies is a code the backend actually emits.
 */

const BACKEND_SRC = join(import.meta.dir, "../../src");

function walk(dir: string): string[] {
  const files: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      files.push(...walk(path));
    } else if (entry.endsWith(".ts") || entry.endsWith(".java")) {
      files.push(path);
    }
  }
  return files;
}

function backendEmittedCodes(): Set<string> {
  const codes = new Set<string>();
  for (const file of walk(BACKEND_SRC)) {
    const source = readFileSync(file, "utf8");
    for (const match of source.matchAll(/new HttpError\(\s*\d+,\s*"([a-z_]+)"/g)) {
      codes.add(match[1]);
    }
  }
  return codes;
}

/**
 * A classified code is a `"snake_case".equals(...)` literal on a line whose
 * nearby context (a short window above, covering `String code =
 * errorCode(...)` locals) mentions errorCode() or ApiError.error(). The
 * underscore requirement plus the context window keeps enum-ish literals like
 * "hosting" or progress phases like "release_finishing" out.
 */
function modClassifiedCodes(): Map<string, string[]> {
  const codes = new Map<string, string[]>();
  for (const file of walk(FABRIC_MAIN_JAVA)) {
    const lines = readFileSync(file, "utf8").split("\n");
    for (const [index, line] of lines.entries()) {
      for (const match of line.matchAll(/"([a-z]+(?:_[a-z]+)+)"\.equals\(/g)) {
        const window = lines.slice(Math.max(0, index - 12), index + 1).join("\n");
        if (/errorCode\(|\.error\(\)/.test(window)) {
          const usages = codes.get(match[1]) ?? [];
          usages.push(`${file.slice(FABRIC_MAIN_JAVA.length + 1)}:${index + 1}`);
          codes.set(match[1], usages);
        }
      }
    }
  }
  return codes;
}

describe("error code parity", () => {
  const emitted = backendEmittedCodes();
  const classified = modClassifiedCodes();

  test("the extractors still find both sides of the contract", () => {
    expect(emitted.size).toBeGreaterThanOrEqual(20);
    // world_not_found, membership_revoked, host_not_active, not_finalizing,
    // invite_not_found, invite_inactive, invite_expired exist today.
    expect(classified.size).toBeGreaterThanOrEqual(7);
    expect([...classified.keys()]).toContain("world_not_found");
    expect([...classified.keys()]).toContain("invite_expired");
  });

  test("every error code the mod classifies is emitted by the backend", () => {
    const unknown = [...classified.entries()]
      .filter(([code]) => !emitted.has(code))
      .map(([code, usages]) => `${code} (classified at ${usages.join(", ")})`);
    expect(unknown).toEqual([]);
  });
});
