import { describe, expect, test } from "bun:test";

import { decodeStorageKey } from "../../src/router.ts";
import { lifecycleRouter } from "../support/router.ts";

describe("authenticated blob routes", () => {
  test("worker blob URLs point at authenticated world storage routes without signature query params", async () => {
    const { WorkerSignedUrlSigner } = await import("../../src/service.ts");
    const signer = new WorkerSignedUrlSigner({
      PUBLIC_BASE_URL: "http://127.0.0.1:8787"
    });

    const signedUpload = await signer.signUpload("world-1", "blobs/08/example.bin", "http://127.0.0.1:8787");
    const url = new URL(signedUpload.url);

    expect(url.pathname).toBe("/worlds/world-1/storage/blob/blobs%2F08%2Fexample.bin");
    expect(url.search).toBe("");
    expect(decodeStorageKey(url.pathname.slice("/worlds/world-1/storage/blob/".length))).toBe("blobs/08/example.bin");
  });

  test("router forwards authenticated blob uploads without requiring query signature semantics", async () => {
    const uploads: Array<{ worldId: string; storageKey: string; contentType: string | null }> = [];
    const router = lifecycleRouter({
      async uploadStorageBlob(ctx: { playerUuid: string }, worldId: string, storageKey: string, request: Request) {
        uploads.push({
          worldId,
          storageKey,
          contentType: request.headers.get("content-type")
        });
        expect(ctx.playerUuid).toBe("player-owner");
      }
    });

    const response = await router(new Request(
      "http://127.0.0.1:8787/worlds/world-1/storage/blob/blobs%2F08%2Fexample.bin?method=PUT&expires=1&signature=ignored",
      {
        method: "PUT",
        headers: {
          authorization: "Bearer session-token",
          "content-type": "application/octet-stream"
        },
        body: "payload"
      }
    ));

    expect(response.status).toBe(204);
    expect(uploads).toEqual([{
      worldId: "world-1",
      storageKey: "blobs/08/example.bin",
      contentType: "application/octet-stream"
    }]);
  });
});
