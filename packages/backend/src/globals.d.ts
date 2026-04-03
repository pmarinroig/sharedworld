declare class URLPattern {
  constructor(init?: { pathname?: string });
  exec(input: string): URLPatternResult | null;
}

interface URLPatternResult {
  pathname: {
    groups: Record<string, string | undefined>;
  };
}

