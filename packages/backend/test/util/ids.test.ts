import { describe, expect, test } from "bun:test";

import { inviteCode, slugify } from "../../src/ids.ts";

describe("slugify", () => {
  test("lowercases and hyphenates a normal name", () => {
    expect(slugify("Friends SMP")).toBe("friends-smp");
  });

  test("collapses runs of non-alphanumerics and trims edge hyphens", () => {
    expect(slugify("  ***Hello,   World!!  ")).toBe("hello-world");
  });

  test("drops characters outside a-z0-9 (no unicode leaks into the slug)", () => {
    const slug = slugify("Café Münster");
    expect(slug).toMatch(/^[a-z0-9-]*$/);
    expect(slug).not.toContain("é");
    expect(slug).not.toContain("ü");
  });

  test("an all-symbol name slugifies to empty (the unique id suffix carries uniqueness)", () => {
    expect(slugify("@#$%^&*()")).toBe("");
  });

  test("caps the slug at 48 characters", () => {
    const slug = slugify("a".repeat(200));
    expect(slug.length).toBe(48);
  });
});

describe("inviteCode", () => {
  test("is three four-character groups using only unambiguous characters", () => {
    const allowed = /^[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}$/;
    for (let i = 0; i < 50; i += 1) {
      const code = inviteCode();
      expect(code).toMatch(allowed);
      // The easily-confused characters I, O, 0, 1 must never appear.
      expect(code).not.toMatch(/[IO01]/);
    }
  });
});
