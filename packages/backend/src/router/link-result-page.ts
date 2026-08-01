/**
 * The browser-facing result page for the Google Drive link callback — the only
 * HTML the backend serves. Kept out of the router plumbing on purpose.
 */
export function renderStorageLinkPage(options: {
  status: number;
  tone: "success" | "error";
  title: string;
  message: string;
  linkedAccountEmail: string | null;
}): Response {
  const accentSoft = options.tone === "success" ? "rgba(92, 127, 104, 0.12)" : "rgba(155, 95, 95, 0.12)";
  const accountMarkup = options.linkedAccountEmail
    ? `
      <div class="account">
        <p class="account-value">${escapeHtml(options.linkedAccountEmail)}</p>
      </div>
    `
    : "";
  const html = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>SharedWorld</title>
    <style>
      :root {
        color-scheme: light;
      }

      * {
        box-sizing: border-box;
      }

      body {
        margin: 0;
        min-height: 100vh;
        font-family: "Avenir Next", "Segoe UI", sans-serif;
        background:
          radial-gradient(circle at top, rgba(123, 161, 144, 0.16), transparent 36%),
          linear-gradient(180deg, #eef3f1 0%, #f6f1e9 100%);
        color: #1f2933;
      }

      main {
        min-height: 100vh;
        display: grid;
        place-items: center;
        padding: 24px;
      }

      .shell {
        width: min(100%, 520px);
      }

      .brand {
        margin: 0 0 16px;
        text-align: center;
        font-size: 0.72rem;
        font-weight: 600;
        letter-spacing: 0.18em;
        text-transform: uppercase;
        color: #6d7c76;
      }

      .card {
        border-radius: 24px;
        border: 1px solid rgba(95, 111, 104, 0.14);
        background: rgba(255, 255, 255, 0.82);
        box-shadow: 0 24px 60px rgba(31, 41, 51, 0.1);
        padding: 36px 32px 32px;
        backdrop-filter: blur(8px);
      }

      h1 {
        margin: 0;
        font-size: clamp(2rem, 4vw, 2.4rem);
        line-height: 1.08;
        color: #14212b;
      }

      .message {
        margin: 14px 0 0;
        font-size: 1rem;
        line-height: 1.55;
        color: #556471;
      }

      .account {
        margin-top: 22px;
        padding-top: 18px;
        border-top: 1px solid ${accentSoft};
      }

      .account-value {
        margin: 0;
        font-size: 1.05rem;
        font-weight: 600;
        line-height: 1.5;
        color: #14212b;
        overflow-wrap: anywhere;
      }

      @media (max-width: 640px) {
        .card {
          padding: 28px 24px 24px;
          border-radius: 20px;
        }
      }
    </style>
  </head>
  <body>
    <main>
      <div class="shell">
        <p class="brand">SharedWorld</p>
        <section class="card">
          <h1>${escapeHtml(options.title)}</h1>
          ${accountMarkup}
          <p class="message">${escapeHtml(options.message)}</p>
        </section>
      </div>
    </main>
  </body>
</html>`;

  return new Response(html, {
    status: options.status,
    headers: {
      "content-type": "text/html; charset=utf-8"
    }
  });
}

function escapeHtml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}
