/**
 * Regenerates `operations.cache.json` from a running stack — `npm run refresh-operations`.
 *
 * The cache exists so the gap spec runs without a stack; this script is the only thing that writes
 * it, and it writes what the services actually serve. Point it elsewhere with QIP_CATALOG_URL,
 * QIP_ENGINE_URL, QIP_SESSIONS_URL and QIP_TESTING_SERVICE_URL.
 *
 * **Four documents, two shapes.** The three Java services publish OpenAPI 3 at `/v3/api-docs`; the
 * testing service publishes **Swagger 2.0** at `/api/v1/swagger/doc.json`. Which document a service
 * serves is `operationServices()`, so this script iterates services and never a path: a refresh that read three of the four would drop all 40 testing-service rows out of
 * the cache, and the next `specs/schema/api-coverage.spec.ts` run would then report those rows as
 * operations the services no longer serve.
 */
import fs from "node:fs";
import path from "node:path";
import { CACHE_FILE, operationServices, operationsFromOpenApi } from "./operations.ts";

/** @type {Record<string, string[]>} */
const services = {};
for (const service of operationServices()) {
  const url = `${service.baseUrl}${service.docPath}`;
  let document;
  try {
    const response = await fetch(url);
    if (!response.ok) {
      console.error(`${service.service}: ${url} answered ${response.status}`);
      process.exit(1);
    }
    // Inside the same guard as the fetch: a 200 carrying HTML — an error page from a proxy, a
    // login redirect — throws here and not at the socket, and it is the same "the stack is not
    // what you think it is" failure.
    document = await response.json();
  } catch (cause) {
    console.error(`${service.service}: ${url} did not answer an OpenAPI document — is the stack up?`);
    console.error(`  ${cause instanceof Error ? cause.message : String(cause)}`);
    process.exit(1);
  }

  const operations = operationsFromOpenApi(document);
  // A document that reduces to nothing is the failure this script must not commit: the cache is
  // what the no-stack gap detector reads, and an empty inventory turns every registry row into a
  // row the services no longer serve.
  if (operations.length === 0) {
    console.error(`${service.service}: ${url} answered 200 but declares no operations`);
    console.error("  refusing to write the cache — the gap detector would then check nothing");
    process.exit(1);
  }

  services[service.service] = operations;
  console.log(`${service.service}: ${operations.length} operations from ${url}`);
}

fs.writeFileSync(CACHE_FILE, `${JSON.stringify({ services }, null, 2)}\n`);
console.log(`wrote ${path.relative(process.cwd(), CACHE_FILE)}`);
