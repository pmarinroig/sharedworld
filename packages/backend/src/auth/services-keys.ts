import { HttpError } from "../http.ts";
import type { Env } from "../env.ts";
import { decodeBase64Field } from "./certificate.ts";

/**
 * Supplies Mojang's player-certificate public keys (the set that signs profile
 * keypairs). Mojang answers 403 to ALL Cloudflare Workers egress — sessionserver
 * and api.minecraftservices.com alike — so the worker NEVER fetches this
 * document itself: the set comes from MOJANG_PLAYER_CERTIFICATE_KEYS
 * (comma-separated base64 DER, the pin/test hook) or from the D1 row that
 * scripts/backend-seed-mojang-keys.sh writes from a developer machine. The
 * seeded set is served indefinitely regardless of age — the keys rotate on the
 * order of years, and a stale answer is far better than a failed login.
 */

export interface ServicesKeyStore {
  getMojangServicesKeys(): Promise<{ fetchedAt: string; keysJson: string } | null>;
}

export interface ServicesKeyProvider {
  playerCertificateKeys(): Promise<Uint8Array[]>;
}

export class MojangServicesKeyProvider implements ServicesKeyProvider {
  constructor(
    private readonly store: ServicesKeyStore,
    private readonly env: Env
  ) {}

  async playerCertificateKeys(): Promise<Uint8Array[]> {
    const pinned = (this.env.MOJANG_PLAYER_CERTIFICATE_KEYS ?? "").trim();
    if (pinned.length > 0) {
      return pinned.split(",").map((entry) => decodeBase64(entry.trim()));
    }

    const cached = await this.store.getMojangServicesKeys();
    if (cached) {
      return parseKeysJson(cached.keysJson);
    }

    console.error(
      "SharedWorld Mojang publickeys cache is empty; run scripts/backend-seed-mojang-keys.sh to seed it"
    );
    throw new HttpError(
      503,
      "identity_verification_unavailable",
      "Minecraft's key registry is not available to the SharedWorld server right now. Please try again in a minute."
    );
  }
}

function parseKeysJson(keysJson: string): Uint8Array[] {
  const keys = JSON.parse(keysJson) as string[];
  return keys.map((key) => decodeBase64(key));
}

function decodeBase64(value: string): Uint8Array {
  return decodeBase64Field(
    value,
    "identity_verification_unavailable",
    "Minecraft's key registry returned an unusable key set.",
    503
  );
}
