import { describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { parseSingleByteRange } from "../../src/storage.ts";
import { createSqliteRepository } from "../support/sqlite-d1.ts";
import { createBlobBucket, createBlobSigner, createTestService } from "../support/service-fixtures.ts";

const OWNER = { playerUuid: "player-owner", playerName: "Owner" };
const KEY = "packs/full/ab/abcdef.pack";
const BYTES = new TextEncoder().encode("0123456789");

async function fixture() {
  const repository = createSqliteRepository();
  await repository.upsertUser({ ...OWNER, createdAt: new Date().toISOString() });
  const world = await repository.createWorld(OWNER, "Friends SMP", "friends-smp");
  const instance = createTestService(repository, createBlobSigner().signer, {
    BLOBS: createBlobBucket({ [KEY]: BYTES.slice() })
  });
  return { instance, worldId: world.id };
}

function rangedRequest(range?: string): Request {
  return new Request("https://example.invalid/blob", range === undefined ? {} : { headers: { range } });
}

describe("blob download Range handling", () => {
  test("no Range serves the whole blob with 200 and advertises resume", async () => {
    const { instance, worldId } = await fixture();
    const response = await instance.downloadStorageBlob(OWNER, worldId, KEY, rangedRequest());
    expect(response.status).toBe(200);
    expect(response.headers.get("accept-ranges")).toBe("bytes");
    expect(response.headers.get("content-length")).toBe("10");
    expect(await response.text()).toBe("0123456789");
  });

  test("an open range resumes from the offset with 206 + Content-Range", async () => {
    const { instance, worldId } = await fixture();
    const response = await instance.downloadStorageBlob(OWNER, worldId, KEY, rangedRequest("bytes=4-"));
    expect(response.status).toBe(206);
    expect(response.headers.get("content-range")).toBe("bytes 4-9/10");
    expect(response.headers.get("content-length")).toBe("6");
    expect(await response.text()).toBe("456789");
  });

  test("a bounded range serves exactly the requested slice", async () => {
    const { instance, worldId } = await fixture();
    const response = await instance.downloadStorageBlob(OWNER, worldId, KEY, rangedRequest("bytes=2-5"));
    expect(response.status).toBe(206);
    expect(response.headers.get("content-range")).toBe("bytes 2-5/10");
    expect(await response.text()).toBe("2345");
  });

  test("a malformed or unsupported Range falls back to the full 200", async () => {
    const { instance, worldId } = await fixture();
    for (const header of ["bytes=5-2", "bytes=-500", "bytes=0-1,4-5", "items=0-4", "garbage"]) {
      const response = await instance.downloadStorageBlob(OWNER, worldId, KEY, rangedRequest(header));
      expect(response.status).toBe(200);
      expect(await response.text()).toBe("0123456789");
    }
  });

  test("a range past the end of the blob is 416 range_not_satisfiable", async () => {
    const { instance, worldId } = await fixture();
    let caught: unknown = null;
    try {
      await instance.downloadStorageBlob(OWNER, worldId, KEY, rangedRequest("bytes=10-"));
    } catch (error) {
      caught = error;
    }
    expect(caught).toBeInstanceOf(HttpError);
    expect((caught as HttpError).status).toBe(416);
    expect((caught as HttpError).code).toBe("range_not_satisfiable");
  });
});

describe("parseSingleByteRange", () => {
  test("accepts the two supported forms and rejects everything else", () => {
    expect(parseSingleByteRange("bytes=0-")).toEqual({ offset: 0, endInclusive: null });
    expect(parseSingleByteRange("bytes=1024-")).toEqual({ offset: 1024, endInclusive: null });
    expect(parseSingleByteRange(" bytes=3-7 ")).toEqual({ offset: 3, endInclusive: 7 });
    expect(parseSingleByteRange(null)).toBeNull();
    expect(parseSingleByteRange(undefined)).toBeNull();
    expect(parseSingleByteRange("")).toBeNull();
    expect(parseSingleByteRange("bytes=-500")).toBeNull();
    expect(parseSingleByteRange("bytes=5-2")).toBeNull();
    expect(parseSingleByteRange("bytes=0-1,4-5")).toBeNull();
    expect(parseSingleByteRange("items=0-4")).toBeNull();
  });
});
