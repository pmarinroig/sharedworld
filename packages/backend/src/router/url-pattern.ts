export type RouteMatch = {
  pathname: {
    groups: Record<string, string>;
  };
};

export type UrlPatternLike = {
  exec(input: string): RouteMatch | null;
};

export type UrlPatternCtor = new (init: { pathname: string }) => UrlPatternLike;

/**
 * The one route-pattern implementation. Production Workers provide a native
 * URLPattern; everywhere else (bun tests, local tooling) this fallback runs —
 * exported so tests exercise exactly the code production falls back to.
 */
export class FallbackURLPattern implements UrlPatternLike {
  private readonly regex: RegExp;
  private readonly groupNames: string[];

  constructor(init: { pathname: string }) {
    const compiled = compilePathPattern(init.pathname);
    this.regex = compiled.regex;
    this.groupNames = compiled.groupNames;
  }

  exec(input: string): RouteMatch | null {
    const url = new URL(input);
    const match = this.regex.exec(url.pathname);
    if (!match) {
      return null;
    }
    const groups: Record<string, string> = {};
    for (const [index, name] of this.groupNames.entries()) {
      groups[name] = match[index + 1] ?? "";
    }
    return {
      pathname: {
        groups
      }
    };
  }
}

const globalUrlPattern = (globalThis as typeof globalThis & { URLPattern?: UrlPatternCtor }).URLPattern;

export const UrlPattern: UrlPatternCtor = globalUrlPattern ?? FallbackURLPattern;

function compilePathPattern(pathname: string): { regex: RegExp; groupNames: string[] } {
  const groupNames: string[] = [];
  const escapedSegments = pathname.split("/").map((segment) => {
    if (!segment.startsWith(":")) {
      return escapeRegex(segment);
    }
    const wildcard = segment.endsWith("*");
    const name = wildcard ? segment.slice(1, -1) : segment.slice(1);
    groupNames.push(name);
    return wildcard ? "(.*)" : "([^/]+)";
  });
  return {
    regex: new RegExp(`^${escapedSegments.join("/")}$`),
    groupNames
  };
}

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}
