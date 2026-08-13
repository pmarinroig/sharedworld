import { describe, expect, test } from "bun:test";

import type { FinalizeSnapshotRequest, SnapshotPack } from "../../../shared/src/index.ts";

import { createSqliteRepository } from "../support/sqlite-d1.ts";
import type { D1SharedWorldRepository } from "../../src/d1-repository.ts";
import { HttpError } from "../../src/http.ts";
import {
  buildManifestDocument,
  parseManifestDocument,
  providerManifestDocumentReader,
  type SnapshotManifestDocument
} from "../../src/manifest-doc.ts";
import type { RequestContext } from "../../src/repository.ts";
import type { BlobRange, StorageBinding, StorageProvider, StoredBlob } from "../../src/storage.ts";
import { persistSnapshot } from "../../src/service/snapshots.ts";
import type { ServiceContext } from "../../src/service/context.ts";

/**
 * 0027 manifest-as-document: pack member lists live in one content-addressed
 * JSON doc in the world's storage instead of per-file D1 rows. These tests
 * pin the write shape (1 snapshots row, ZERO member rows), byte-identical
 * assembled manifests across representations, the loud-failure read path,
 * the legacy-over-doc inheritance guard, GC's shared-doc reference leg, and
 * the persistSnapshot service lane (dedupe, kill-switch, fallback).
 */

const OWNER: RequestContext = { playerUuid: "player-owner", playerName: "Owner" };

function pack(overrides: Partial<SnapshotPack> = {}): SnapshotPack {
  return {
    packId: "non-region",
    hash: "pack-hash-1",
    size: 40,
    storageKey: "packs/full/one.pack",
    transferMode: "pack-full",
    files: [
      { path: "level.dat", hash: "hash-level", size: 25, contentType: "application/octet-stream" },
      { path: "session.lock", hash: "hash-lock", size: 15, contentType: "application/octet-stream" }
    ],
    ...overrides
  };
}

function regionBundle(overrides: Partial<SnapshotPack> = {}): SnapshotPack {
  return pack({
    packId: "region-bundle-r.0.0",
    hash: "bundle-hash-1",
    size: 64,
    storageKey: "region-bundles/full/two.pack",
    files: [
      { path: "region/r.0.0.mca", hash: "hash-r00", size: 64, contentType: "application/octet-stream" }
    ],
    ...overrides
  });
}

function finalizeRequest(packs: SnapshotPack[], baseSnapshotId: string | null = null): FinalizeSnapshotRequest {
  return { files: [], packs, baseSnapshotId };
}

/**
 * Minimal drive-shaped provider: Map-backed objects, storage_objects rows
 * recorded on put like the real provider, StoredBlob.arrayBuffer() on get.
 */
function fakeDriveProvider(repository: D1SharedWorldRepository) {
  const objects = new Map<string, Uint8Array>();
  let putCount = 0;
  const provider: StorageProvider = {
    provider: "google-drive",
    async exists(_binding, storageKey) {
      return objects.has(storageKey);
    },
    async put(binding, storageKey, body, contentType) {
      putCount += 1;
      const bytes = body instanceof Uint8Array
        ? body
        : new Uint8Array(body instanceof ArrayBuffer ? body : await new Response(body as BodyInit).arrayBuffer());
      objects.set(storageKey, bytes);
      if (binding.storageAccountId != null) {
        await repository.upsertStorageObject({
          provider: "google-drive",
          storageAccountId: binding.storageAccountId,
          storageKey,
          objectId: `fake-${storageKey}`,
          contentType,
          size: bytes.byteLength,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString()
        });
      }
    },
    async get(_binding: StorageBinding, storageKey: string, _range?: BlobRange | null): Promise<StoredBlob | null> {
      const bytes = objects.get(storageKey);
      if (!bytes) {
        return null;
      }
      return {
        body: null,
        contentType: "application/json",
        size: bytes.byteLength,
        status: 200,
        contentRange: null,
        async arrayBuffer() {
          const copy = new Uint8Array(bytes.byteLength);
          copy.set(bytes);
          return copy.buffer;
        }
      };
    },
    async delete(_binding, storageKey) {
      objects.delete(storageKey);
    },
    async quota() {
      return { usedBytes: null, totalBytes: null };
    }
  };
  return { provider, objects, putCount: () => putCount };
}

/** Repository + attached fake-drive reader + a drive-linked world. */
async function docFixture() {
  const repository = createSqliteRepository();
  const drive = fakeDriveProvider(repository);
  repository.attachManifestDocumentReader(providerManifestDocumentReader(drive.provider));
  const world = await repository.createWorld(OWNER, "Doc SMP", "doc-smp");
  await repository.createOrUpdateStorageAccount({
    id: "acct-1",
    provider: "google-drive",
    ownerPlayerUuid: OWNER.playerUuid,
    externalAccountId: "ext-1",
    email: null,
    displayName: null,
    accessToken: null,
    refreshToken: null,
    tokenExpiresAt: null,
    createdAt: "2026-01-01T00:00:00.000Z",
    updatedAt: "2026-01-01T00:00:00.000Z"
  });
  repository.raw.exec(`UPDATE worlds SET storage_account_id = 'acct-1' WHERE id = '${world.id}'`);
  return { repository, drive, world };
}

/** Uploads the request's doc through the fake provider, then doc-finalizes. */
async function finalizeDocSnapshot(
  fixture: Awaited<ReturnType<typeof docFixture>>,
  request: FinalizeSnapshotRequest,
  now: Date
) {
  const built = await buildManifestDocument(request.packs ?? []);
  await fixture.drive.provider.put(
    { provider: "google-drive", storageAccountId: "acct-1" },
    built.storageKey,
    built.bytes,
    "application/json"
  );
  const manifest = await fixture.repository.finalizeSnapshot(fixture.world.id, OWNER, request, now, {
    manifestStorageKey: built.storageKey
  });
  return { manifest, storageKey: built.storageKey };
}

function memberRowCount(repository: ReturnType<typeof createSqliteRepository>, snapshotId: string): number {
  const row = repository.raw
    .query("SELECT COUNT(*) AS n FROM snapshot_files WHERE snapshot_id = ? AND pack_id IS NOT NULL")
    .get(snapshotId) as { n: number };
  return Number(row.n);
}

describe("manifest document format", () => {
  test("canonical bytes are order-independent and content-addressed", async () => {
    const shuffled = [regionBundle(), pack()];
    const ordered = [pack(), regionBundle()];
    const a = await buildManifestDocument(shuffled);
    const b = await buildManifestDocument(ordered);
    expect(a.storageKey).toBe(b.storageKey);
    expect(a.storageKey).toMatch(/^manifests\/[0-9a-f]{2}\/[0-9a-f]{64}\.json$/);
    const parsed = parseManifestDocument(a.bytes.buffer as ArrayBuffer);
    expect(parsed.packs.map((entry) => entry.packId)).toEqual(["non-region", "region-bundle-r.0.0"]);
    expect(parsed.packs[0].files.map((file) => file.path)).toEqual(["level.dat", "session.lock"]);
  });

  test("an unknown format version fails loud instead of reading as empty", () => {
    const bytes = new TextEncoder().encode(JSON.stringify({ formatVersion: 999, packs: [] }));
    expect(() => parseManifestDocument(bytes.buffer)).toThrow("unsupported format");
  });
});

describe("doc-format finalize (repository)", () => {
  test("writes one snapshots row and ZERO pack-member rows; membersSnapshotId stays null", async () => {
    const fixture = await docFixture();
    const { manifest } = await finalizeDocSnapshot(fixture, finalizeRequest([pack(), regionBundle()]), new Date("2026-01-01T01:00:00.000Z"));

    expect(memberRowCount(fixture.repository, manifest.snapshotId)).toBe(0);
    const looseRows = fixture.repository.raw
      .query("SELECT COUNT(*) AS n FROM snapshot_files WHERE snapshot_id = ?")
      .get(manifest.snapshotId) as { n: number };
    expect(Number(looseRows.n)).toBe(0);

    const snapshotRow = fixture.repository.raw
      .query("SELECT packs_json, manifest_storage_key FROM snapshots WHERE id = ?")
      .get(manifest.snapshotId) as { packs_json: string; manifest_storage_key: string };
    expect(snapshotRow.manifest_storage_key).toMatch(/^manifests\//);
    const directory = JSON.parse(snapshotRow.packs_json) as Array<{ membersSnapshotId: unknown; memberCount: number }>;
    expect(directory).toHaveLength(2);
    for (const entry of directory) {
      expect(entry.membersSnapshotId).toBeNull();
    }

    // The served manifest carries full member lists resolved from the doc.
    expect(manifest.packs.map((entry) => entry.files.length)).toEqual([2, 1]);
  });

  test("assembled manifests are byte-identical across doc and legacy representations", async () => {
    const request = finalizeRequest([pack(), regionBundle()]);
    const now = new Date("2026-01-01T01:00:00.000Z");

    const docFixt = await docFixture();
    const { manifest: docManifest } = await finalizeDocSnapshot(docFixt, request, now);

    const legacyRepository = createSqliteRepository();
    const legacyWorld = await legacyRepository.createWorld(OWNER, "Legacy SMP", "legacy-smp");
    const legacyManifest = await legacyRepository.finalizeSnapshot(legacyWorld.id, OWNER, request, now);

    const normalize = (manifest: { snapshotId: string; worldId: string }) =>
      JSON.stringify(manifest).replaceAll(manifest.snapshotId, "SNAP").replaceAll(manifest.worldId, "WORLD");
    expect(normalize(docManifest)).toBe(normalize(legacyManifest));

    // Uncached reload reproduces the same bytes (Workers cache immutability).
    const reloaded = await docFixt.repository.getSnapshot(docFixt.world.id, docManifest.snapshotId);
    expect(JSON.stringify(reloaded)).toBe(JSON.stringify(docManifest));
  });

  test("a missing document fails loud with snapshot_manifest_unavailable, never empty members", async () => {
    const fixture = await docFixture();
    const { manifest, storageKey } = await finalizeDocSnapshot(fixture, finalizeRequest([pack()]), new Date("2026-01-01T01:00:00.000Z"));
    await fixture.drive.provider.delete({ provider: "google-drive", storageAccountId: "acct-1" }, storageKey);

    let caught: unknown = null;
    try {
      await fixture.repository.getSnapshot(fixture.world.id, manifest.snapshotId);
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(502);
    expect((caught as HttpError).code).toBe("snapshot_manifest_unavailable");
  });

  test("a missing document never blocks the write pipeline: headers load and the world heals by snapshotting again", async () => {
    const fixture = await docFixture();
    const first = await finalizeDocSnapshot(fixture, finalizeRequest([pack()]), new Date("2026-01-01T01:00:00.000Z"));
    await fixture.drive.provider.delete({ provider: "google-drive", storageAccountId: "acct-1" }, first.storageKey);

    // Upload planning and finalize validation read headers only — no doc.
    const headers = await fixture.repository.getLatestSnapshotHeaders(fixture.world.id);
    expect(headers?.snapshotId).toBe(first.manifest.snapshotId);
    expect(headers?.packs[0]?.hash).toBe("pack-hash-1");
    expect(headers?.packs[0]?.files).toEqual([]);

    // The next finalize lands a fresh doc and becomes the readable latest.
    const healed = await finalizeDocSnapshot(
      fixture,
      finalizeRequest([pack({ hash: "pack-hash-2", storageKey: "packs/full/three.pack" })], first.manifest.snapshotId),
      new Date("2026-01-01T02:00:00.000Z")
    );
    const served = await fixture.repository.getLatestSnapshot(fixture.world.id);
    expect(served?.snapshotId).toBe(healed.manifest.snapshotId);
    expect(served?.packs[0]?.files).toHaveLength(2);
  });

  test("a document that lacks a directory pack fails loud", async () => {
    const fixture = await docFixture();
    const { manifest, storageKey } = await finalizeDocSnapshot(fixture, finalizeRequest([pack()]), new Date("2026-01-01T01:00:00.000Z"));
    // Corrupt: replace the object with a doc for a DIFFERENT pack id.
    const wrong: SnapshotManifestDocument = { formatVersion: 1, packs: [{ packId: "someone-else", files: [] }] };
    fixture.drive.objects.set(storageKey, new TextEncoder().encode(JSON.stringify(wrong)));

    await expect(fixture.repository.getSnapshot(fixture.world.id, manifest.snapshotId)).rejects.toThrow(
      "does not match the snapshot's pack directory"
    );
  });
});

describe("legacy finalize over a doc-format base (inheritance guard)", () => {
  test("materializes every member row from the request instead of inheriting from a row-less base", async () => {
    const fixture = await docFixture();
    const base = await finalizeDocSnapshot(fixture, finalizeRequest([pack()]), new Date("2026-01-01T01:00:00.000Z"));

    // Doc write unavailable for the next autosave: legacy-mode finalize on
    // top of the doc base, identical pack (would have inherited pre-0027).
    const heir = await fixture.repository.finalizeSnapshot(
      fixture.world.id,
      OWNER,
      finalizeRequest([pack()], base.manifest.snapshotId),
      new Date("2026-01-01T02:00:00.000Z")
    );

    expect(memberRowCount(fixture.repository, heir.snapshotId)).toBe(2);
    const heirRow = fixture.repository.raw
      .query("SELECT packs_json, manifest_storage_key FROM snapshots WHERE id = ?")
      .get(heir.snapshotId) as { packs_json: string; manifest_storage_key: string | null };
    expect(heirRow.manifest_storage_key).toBeNull();
    const directory = JSON.parse(heirRow.packs_json) as Array<{ membersSnapshotId: unknown }>;
    expect(directory[0].membersSnapshotId).toBeNull();

    // Both manifests stay fully readable.
    const heirManifest = await fixture.repository.getSnapshot(fixture.world.id, heir.snapshotId);
    expect(heirManifest?.packs[0]?.files.map((file) => file.path)).toEqual(["level.dat", "session.lock"]);
  });
});

describe("GC over doc snapshots", () => {
  test("isStorageKeyReferenced sees the doc pointer", async () => {
    const fixture = await docFixture();
    const { storageKey } = await finalizeDocSnapshot(fixture, finalizeRequest([pack()]), new Date("2026-01-01T01:00:00.000Z"));
    expect(await fixture.repository.isStorageKeyReferenced(storageKey)).toBe(true);
    expect(await fixture.repository.isStorageKeyReferenced("manifests/00/absent.json")).toBe(false);
  });

  test("deleting the only referencer reclaims the doc key; a shared doc survives", async () => {
    const fixture = await docFixture();
    const now = new Date("2026-01-01T01:00:00.000Z");
    const first = await finalizeDocSnapshot(fixture, finalizeRequest([pack()]), now);
    // Restore-shaped: a second snapshot with identical members points at the
    // SAME content-addressed doc.
    const second = await finalizeDocSnapshot(fixture, finalizeRequest([pack()], first.manifest.snapshotId), new Date("2026-01-01T02:00:00.000Z"));
    expect(second.storageKey).toBe(first.storageKey);

    const firstDeletion = await fixture.repository.deleteSnapshots(fixture.world.id, [first.manifest.snapshotId]);
    // The doc is still referenced by the surviving snapshot.
    expect(firstDeletion.unreferencedStorageKeys).not.toContain(first.storageKey);

    const secondDeletion = await fixture.repository.deleteSnapshots(fixture.world.id, [second.manifest.snapshotId]);
    // Last referencer gone: the doc key is reclaimable (the pack blob too).
    expect(secondDeletion.unreferencedStorageKeys).toContain(first.storageKey);
    expect(secondDeletion.unreferencedStorageKeys).toContain("packs/full/one.pack");
  });

  test("promotion machinery never fires for doc snapshots and legacy pairs still promote", async () => {
    const fixture = await docFixture();
    const now = new Date("2026-01-01T01:00:00.000Z");
    // Legacy donor + legacy heir (inheriting member rows) alongside a doc snapshot.
    const donor = await fixture.repository.finalizeSnapshot(fixture.world.id, OWNER, finalizeRequest([pack()]), now);
    const heir = await fixture.repository.finalizeSnapshot(
      fixture.world.id,
      OWNER,
      finalizeRequest([pack()], donor.snapshotId),
      new Date("2026-01-01T02:00:00.000Z")
    );
    expect(memberRowCount(fixture.repository, heir.snapshotId)).toBe(0);
    const docSnap = await finalizeDocSnapshot(fixture, finalizeRequest([regionBundle()], heir.snapshotId), new Date("2026-01-01T03:00:00.000Z"));

    // Deleting the legacy donor promotes its member rows to the legacy heir;
    // the doc snapshot is untouched (no member rows before or after).
    await fixture.repository.deleteSnapshots(fixture.world.id, [donor.snapshotId]);
    expect(memberRowCount(fixture.repository, heir.snapshotId)).toBe(2);
    expect(memberRowCount(fixture.repository, docSnap.manifest.snapshotId)).toBe(0);
    const heirManifest = await fixture.repository.getSnapshot(fixture.world.id, heir.snapshotId);
    expect(heirManifest?.packs[0]?.files).toHaveLength(2);
    const docManifest = await fixture.repository.getSnapshot(fixture.world.id, docSnap.manifest.snapshotId);
    expect(docManifest?.packs[0]?.files).toHaveLength(1);
  });
});

describe("client-visible numbers are representation-independent", () => {
  test("snapshot summaries and storage usage match for identical logical content", async () => {
    const request = finalizeRequest([pack(), regionBundle()]);
    const now = new Date("2026-01-01T01:00:00.000Z");

    const docFixt = await docFixture();
    await finalizeDocSnapshot(docFixt, request, now);

    const legacyRepository = createSqliteRepository();
    const legacyWorld = await legacyRepository.createWorld(OWNER, "Legacy SMP", "legacy-smp");
    await legacyRepository.createOrUpdateStorageAccount({
      id: "acct-2",
      provider: "google-drive",
      ownerPlayerUuid: OWNER.playerUuid,
      externalAccountId: "ext-2",
      email: null,
      displayName: null,
      accessToken: null,
      refreshToken: null,
      tokenExpiresAt: null,
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z"
    });
    legacyRepository.raw.exec(`UPDATE worlds SET storage_account_id = 'acct-2' WHERE id = '${legacyWorld.id}'`);
    await legacyRepository.finalizeSnapshot(legacyWorld.id, OWNER, request, now);

    // Identical pack blobs registered on both accounts so usedBytes compares.
    for (const [repository, accountId] of [
      [docFixt.repository, "acct-1"],
      [legacyRepository, "acct-2"]
    ] as const) {
      for (const blob of [pack(), regionBundle()]) {
        await repository.upsertStorageObject({
          provider: "google-drive",
          storageAccountId: accountId,
          storageKey: blob.storageKey,
          objectId: `obj-${blob.storageKey}`,
          contentType: "application/octet-stream",
          size: blob.size,
          createdAt: now.toISOString(),
          updatedAt: now.toISOString()
        });
      }
    }

    const strip = (summaries: Awaited<ReturnType<D1SharedWorldRepository["listSnapshotSummaries"]>>) =>
      summaries.map(({ fileCount, totalSize, totalCompressedSize }) => ({ fileCount, totalSize, totalCompressedSize }));
    expect(strip(await docFixt.repository.listSnapshotSummaries(docFixt.world.id)))
      .toEqual(strip(await legacyRepository.listSnapshotSummaries(legacyWorld.id)));

    // Pack/member numbers are representation-independent; usage additionally
    // counts the manifest document's own bytes (honest accounting — the doc
    // genuinely occupies the user's Drive).
    const docUsage = await docFixt.repository.getStorageUsage(docFixt.world.id);
    const legacyUsage = await legacyRepository.getStorageUsage(legacyWorld.id);
    const docRow = docFixt.repository.raw
      .query("SELECT manifest_storage_key AS k FROM snapshots LIMIT 1")
      .get() as { k: string };
    const docBytes = docFixt.drive.objects.get(docRow.k)?.byteLength ?? 0;
    expect(docBytes).toBeGreaterThan(0);
    expect(docUsage.usedBytes).toBe(legacyUsage.usedBytes + docBytes);
  });
});

describe("persistSnapshot service lane", () => {
  function serviceContextFor(fixture: Awaited<ReturnType<typeof docFixture>>, env: Record<string, string>) {
    // persistSnapshot only touches repository/storageProvider/env; the rest
    // of the context is irrelevant to this lane.
    return {
      repository: fixture.repository,
      storageProvider: fixture.drive.provider,
      env
    } as unknown as ServiceContext;
  }

  test("writes the doc once, dedupes identical members, and records the pointer", async () => {
    const fixture = await docFixture();
    const svc = serviceContextFor(fixture, {});
    const now = new Date("2026-01-01T01:00:00.000Z");

    const first = await persistSnapshot(svc, fixture.world.id, OWNER, finalizeRequest([pack()]), now);
    expect(memberRowCount(fixture.repository, first.snapshotId)).toBe(0);
    expect(fixture.drive.putCount()).toBe(1);

    // Identical members → identical doc key → zero additional puts.
    const second = await persistSnapshot(
      svc,
      fixture.world.id,
      OWNER,
      finalizeRequest([pack()], first.snapshotId),
      new Date("2026-01-01T02:00:00.000Z")
    );
    expect(memberRowCount(fixture.repository, second.snapshotId)).toBe(0);
    expect(fixture.drive.putCount()).toBe(1);
  });

  test("a failed doc write falls back to legacy rows and the snapshot still lands", async () => {
    const fixture = await docFixture();
    const svc = serviceContextFor(fixture, {});
    const originalPut = fixture.drive.provider.put.bind(fixture.drive.provider);
    fixture.drive.provider.put = async () => {
      throw new Error("drive is down");
    };
    try {
      const manifest = await persistSnapshot(svc, fixture.world.id, OWNER, finalizeRequest([pack()]), new Date("2026-01-01T01:00:00.000Z"));
      expect(memberRowCount(fixture.repository, manifest.snapshotId)).toBe(2);
      const served = await fixture.repository.getSnapshot(fixture.world.id, manifest.snapshotId);
      expect(served?.packs[0]?.files).toHaveLength(2);
    } finally {
      fixture.drive.provider.put = originalPut;
    }
  });

  test("an unlinked world stays row-based (the legacy write lane keeps coverage)", async () => {
    const fixture = await docFixture();
    fixture.repository.raw.exec(`UPDATE worlds SET storage_account_id = NULL WHERE id = '${fixture.world.id}'`);
    const svc = serviceContextFor(fixture, {});
    const manifest = await persistSnapshot(svc, fixture.world.id, OWNER, finalizeRequest([pack()]), new Date("2026-01-01T01:00:00.000Z"));
    expect(fixture.drive.putCount()).toBe(0);
    expect(memberRowCount(fixture.repository, manifest.snapshotId)).toBe(2);
  });
});
