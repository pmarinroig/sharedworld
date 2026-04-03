export function randomId(prefix: string): string {
  return `${prefix}_${crypto.randomUUID().replace(/-/g, "")}`;
}

export function randomServerId(): string {
  return crypto.randomUUID().replace(/-/g, "");
}

export function inviteCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const segments: string[] = [];
  for (let i = 0; i < 3; i += 1) {
    let segment = "";
    for (let j = 0; j < 4; j += 1) {
      const index = crypto.getRandomValues(new Uint8Array(1))[0] % alphabet.length;
      segment += alphabet[index];
    }
    segments.push(segment);
  }
  return segments.join("-");
}

export function slugify(name: string): string {
  return name
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 48);
}
