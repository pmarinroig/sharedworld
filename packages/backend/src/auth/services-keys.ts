import { HttpError } from "../http.ts";
import type { Env } from "../env.ts";
import { decodeBase64Field } from "./certificate.ts";

/**
 * Supplies Mojang's player-certificate public keys (the set that signs profile
 * keypairs). Unlike sessionserver, api.minecraftservices.com/publickeys is a
 * rarely-changing document, so a stale answer is far better than a failed
 * login: the fetched set is cached in D1 with a long TTL and served stale
 * indefinitely when Mojang is unreachable. MOJANG_PLAYER_CERTIFICATE_KEYS
 * (comma-separated base64 DER) pins the set outright — the test hook and the
 * emergency lever if Mojang ever blocks this endpoint for Workers too.
 */

export interface ServicesKeyStore {
  getMojangServicesKeys(): Promise<{ fetchedAt: string; keysJson: string } | null>;
  putMojangServicesKeys(fetchedAt: string, keysJson: string): Promise<void>;
}

export interface ServicesKeyProvider {
  playerCertificateKeys(now: Date): Promise<Uint8Array[]>;
}

const CACHE_TTL_MS = 24 * 60 * 60_000;
const FETCH_TIMEOUT_MS = 5_000;
const DEFAULT_ENDPOINT = "https://api.minecraftservices.com/publickeys";

export class MojangServicesKeyProvider implements ServicesKeyProvider {
  constructor(
    private readonly store: ServicesKeyStore,
    private readonly env: Env
  ) {}

  async playerCertificateKeys(now: Date): Promise<Uint8Array[]> {
    const pinned = (this.env.MOJANG_PLAYER_CERTIFICATE_KEYS ?? "").trim();
    if (pinned.length > 0) {
      return pinned.split(",").map((entry) => decodeBase64(entry.trim()));
    }

    const cached = await this.store.getMojangServicesKeys();
    if (cached && now.getTime() - new Date(cached.fetchedAt).getTime() < CACHE_TTL_MS) {
      return parseKeysJson(cached.keysJson);
    }

    try {
      const keysJson = await this.fetchKeySet();
      await this.store.putMojangServicesKeys(now.toISOString(), keysJson);
      return parseKeysJson(keysJson);
    } catch (error) {
      if (cached) {
        console.warn("SharedWorld Mojang publickeys refresh failed; serving stale key set", {
          fetchedAt: cached.fetchedAt,
          cause: String(error)
        });
        return parseKeysJson(cached.keysJson);
      }
      console.warn("SharedWorld Mojang publickeys fetch failed with no cached set", { cause: String(error) });
      throw new HttpError(
        503,
        "identity_verification_unavailable",
        "Minecraft's key registry is unreachable right now. Please try again in a minute."
      );
    }
  }

  private async fetchKeySet(): Promise<string> {
    const endpoint = this.env.MOJANG_SERVICES_PUBLICKEYS_ENDPOINT ?? DEFAULT_ENDPOINT;
    const response = await fetch(endpoint, {
      headers: { accept: "application/json" },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS)
    });
    if (!response.ok) {
      throw new Error(`publickeys returned HTTP ${response.status}`);
    }
    const payload = (await response.json()) as { playerCertificateKeys?: Array<{ publicKey?: string }> };
    const keys = (payload.playerCertificateKeys ?? [])
      .map((entry) => entry.publicKey)
      .filter((key): key is string => typeof key === "string" && key.length > 0);
    if (keys.length === 0) {
      throw new Error("publickeys response contained no playerCertificateKeys");
    }
    return JSON.stringify(keys);
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
