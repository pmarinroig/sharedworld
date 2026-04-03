import { describe, expect, test } from "bun:test";

import { HttpError } from "../../src/http.ts";
import { createRouter } from "../../src/router.ts";
import { createRouterService, storageLinkSessionFixture } from "../support/router.ts";

describe("router error handling", () => {
  test("storage callback success returns polished html and escapes the linked email", async () => {
    const router = createRouter(createRouterService({
      async completeStorageLink() {
        return storageLinkSessionFixture({
          linkedAccountEmail: "<owner>&\"test'@example.com"
        });
      }
    }));

    const response = await router(new Request("http://127.0.0.1:8787/storage/google/callback?sessionId=session-123&code=oauth-code"));
    const body = await response.text();

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("text/html");
    expect(body).toContain("Google Drive linked");
    expect(body).toContain("Return to Minecraft.");
    expect(body).toContain("&lt;owner&gt;&amp;&quot;test&#39;@example.com");
    expect(body).not.toContain("<owner>&\"test'@example.com");
  });

  test("storage callback failure returns html without affecting router-wide json errors", async () => {
    const router = createRouter(createRouterService({
      async completeStorageLink() {
        throw new HttpError(410, "storage_link_expired", "Storage link session expired.");
      },
      async redeemInvite() {
        throw new HttpError(409, "invite_inactive", "Invite code is no longer active.");
      }
    }));

    const callbackResponse = await router(new Request("http://127.0.0.1:8787/storage/google/callback?sessionId=session-123"));
    const callbackBody = await callbackResponse.text();

    expect(callbackResponse.status).toBe(410);
    expect(callbackResponse.headers.get("content-type")).toContain("text/html");
    expect(callbackBody).toContain("Link failed");
    expect(callbackBody).toContain("Storage link session expired.");

    const apiResponse = await router(new Request("http://127.0.0.1:8787/invites/redeem", {
      method: "POST",
      headers: {
        authorization: "Bearer session-token",
        "content-type": "application/json"
      },
      body: JSON.stringify({ code: "ABCD-EFGH-JKLM" })
    }));

    expect(apiResponse.status).toBe(409);
    expect(apiResponse.headers.get("content-type")).toContain("application/json");
    await expect(apiResponse.json()).resolves.toEqual({
      error: "invite_inactive",
      message: "Invite code is no longer active.",
      status: 409
    });
  });

  test("redeem invite returns HttpError status instead of 500", async () => {
    const router = createRouter(createRouterService({
      async redeemInvite() {
        throw new HttpError(409, "invite_inactive", "Invite code is no longer active.");
      }
    }));

    const response = await router(new Request("http://127.0.0.1:8787/invites/redeem", {
      method: "POST",
      headers: {
        authorization: "Bearer session-token",
        "content-type": "application/json"
      },
      body: JSON.stringify({ code: "ABCD-EFGH-JKLM" })
    }));

    expect(response.status).toBe(409);
    expect(response.headers.get("content-type")).toContain("application/json");
    await expect(response.json()).resolves.toEqual({
      error: "invite_inactive",
      message: "Invite code is no longer active.",
      status: 409
    });
  });
});
