import { createIntegrationTestApp } from "./app.ts";

const port = Number.parseInt(process.env.SHAREDWORLD_INTEGRATION_PORT ?? "18787", 10);
const baseUrl = `http://127.0.0.1:${port}`;
const app = createIntegrationTestApp(baseUrl);

const server = Bun.serve({
  port,
  fetch(request) {
    return app.fetch(request);
  }
});

console.log(`SharedWorld integration backend listening on ${baseUrl}`);

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.on(signal, () => {
    server.stop(true);
    process.exit(0);
  });
}
