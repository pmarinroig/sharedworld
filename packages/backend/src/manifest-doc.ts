import type { PackedManifestFile, SnapshotPack } from "../../shared/src/index.ts";

import { HttpError } from "./http.ts";
import type { StorageBinding, StorageProvider } from "./storage.ts";

/**
 * Manifest-as-document (0027): the snapshot's pack MEMBER lists live in one
 * content-addressed JSON object in the world's own storage provider instead
 * of per-file D1 rows. The document deliberately carries NO snapshot
 * identity and NO pack headers: headers stay solely in snapshots.packs_json
 * (one source of truth, readable without a provider round-trip), and an
 * identity-free document hashes identically for identical content — so a
 * restore, whose members are unchanged, reuses the existing object at zero
 * cost instead of uploading a duplicate.
 *
 * Canonical bytes are JSON.stringify of the document with packs sorted by
 * packId (localeCompare) and files by path — matching the ordering the
 * legacy row loader produces, because assembled manifests must stay
 * byte-identical per snapshot id (the Workers manifest cache assumes
 * immutability).
 */

export const MANIFEST_DOCUMENT_FORMAT_VERSION = 1;

export interface SnapshotManifestDocument {
  formatVersion: typeof MANIFEST_DOCUMENT_FORMAT_VERSION;
  packs: Array<{ packId: string; files: PackedManifestFile[] }>;
}

export interface BuiltManifestDocument {
  bytes: Uint8Array;
  storageKey: string;
}

export function manifestDocumentStorageKey(hash: string): string {
  return `manifests/${hash.slice(0, 2)}/${hash}.json`;
}

/** Members-only projection of the finalize request's packs, canonicalized. */
export async function buildManifestDocument(
  packs: ReadonlyArray<Pick<SnapshotPack, "packId" | "files">>
): Promise<BuiltManifestDocument> {
  const document: SnapshotManifestDocument = {
    formatVersion: MANIFEST_DOCUMENT_FORMAT_VERSION,
    packs: [...packs]
      .sort((a, b) => a.packId.localeCompare(b.packId))
      .map((pack) => ({
        packId: pack.packId,
        files: [...pack.files]
          .sort((a, b) => (a.path < b.path ? -1 : a.path > b.path ? 1 : 0))
          .map((file) => ({
            path: file.path,
            hash: file.hash,
            size: file.size,
            contentType: file.contentType ?? "application/octet-stream"
          }))
      }))
  };
  const bytes = new TextEncoder().encode(JSON.stringify(document));
  return { bytes, storageKey: manifestDocumentStorageKey(await sha256Hex(bytes)) };
}

export function parseManifestDocument(bytes: ArrayBuffer): SnapshotManifestDocument {
  let parsed: SnapshotManifestDocument;
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes)) as SnapshotManifestDocument;
  } catch {
    throw manifestUnavailable("Snapshot manifest document is not valid JSON.");
  }
  if (parsed?.formatVersion !== MANIFEST_DOCUMENT_FORMAT_VERSION || !Array.isArray(parsed.packs)) {
    // A future format version must never be silently misread as empty
    // member lists — that would corrupt download plans, not 404 them.
    throw manifestUnavailable("Snapshot manifest document has an unsupported format.");
  }
  return parsed;
}

/**
 * Loads a snapshot's manifest document. Returns null only when the object
 * genuinely does not exist; transport failures throw (the provider's get()
 * carries its own retry ladder for response establishment).
 */
export interface SnapshotManifestDocumentReader {
  load(binding: StorageBinding, storageKey: string): Promise<SnapshotManifestDocument | null>;
}

export function providerManifestDocumentReader(provider: StorageProvider): SnapshotManifestDocumentReader {
  return {
    async load(binding, storageKey) {
      const blob = await provider.get(binding, storageKey);
      if (blob == null) {
        return null;
      }
      // First call site that buffers a StoredBlob: manifest documents are
      // ~100KB-2MB, far under isolate memory limits (world blobs keep
      // streaming through the relay untouched).
      return parseManifestDocument(await blob.arrayBuffer());
    }
  };
}

export function manifestUnavailable(message: string): HttpError {
  return new HttpError(502, "snapshot_manifest_unavailable", message);
}

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  const digest = await crypto.subtle.digest("SHA-256", copy.buffer);
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, "0")).join("");
}
