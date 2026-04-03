import { describe, expect, test } from "bun:test";
import { readFileSync } from "node:fs";

function source(path: string) {
  return readFileSync(new URL(path, import.meta.url), "utf8");
}

describe("legacy lifecycle leakage regressions", () => {
  test("router no longer exposes removed lifecycle endpoints", () => {
    const router = source("../../src/router.ts");
    expect(router).not.toContain("/claim-host");
    expect(router).not.toContain("/active-host");
    expect(router).not.toContain("/handoff-ready");
    expect(router).not.toContain("/join-resolution");
  });

  test("mod production API surface no longer references removed lifecycle dto or client methods", () => {
    const apiClient = source("../../../fabric/src/main/java/link/sharedworld/api/SharedWorldApiClient.java");
    const models = source("../../../fabric/src/main/java/link/sharedworld/api/SharedWorldModels.java");
    const screen = source("../../../fabric/src/main/java/link/sharedworld/screen/SharedWorldScreen.java");

    for (const forbidden of ["resolveJoin(", "claimHost(", "activeHost(", "setWaiting("]) {
      expect(apiClient).not.toContain(forbidden);
      expect(screen).not.toContain(forbidden);
    }
    for (const forbiddenType of ["JoinResolutionDto", "HostLeaseDto", "HostStatusDto", "ClaimHostResponseDto"]) {
      expect(models).not.toContain(forbiddenType);
      expect(screen).not.toContain(forbiddenType);
    }
  });

  test("production-surface lifecycle tests are not allowed to monkey-patch SharedWorldService.prototype", () => {
    const productionLifecycleTests = source("../service/production-parity.test.ts");
    const routerLifecycleTests = source("../router/lifecycle-contracts.test.ts");

    expect(productionLifecycleTests).not.toContain("SharedWorldService.prototype");
    expect(routerLifecycleTests).not.toContain("SharedWorldService.prototype");
  });
});
