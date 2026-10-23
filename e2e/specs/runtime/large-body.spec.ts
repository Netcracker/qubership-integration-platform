/**
 * An 8 MiB (8388608 bytes) request body reaches a chain whole: the echo returns it byte for byte,
 * and a script reads every byte of it.
 *
 * The limits on the way:
 * - the classic engine's Tomcat bounds only the headers (`server.max-http-request-header-size` in
 *   `engine/src/main/resources/application.yml`), not the body;
 * - the micro engine keeps the Quarkus default `quarkus.http.limits.max-body-size` of 10 MiB, and
 *   the case sends less than that;
 * - `env.chainUrl` calls the classic engine on its own port, and a micro domain through the chart's
 *   nginx (`/e2e/svc/`), whose `client_max_body_size` is 850m;
 * - the stack's nginx on 8080 does not proxy `/routes/`, so that path has no case.
 *
 * The two chains are their own, deployed with session logging `OFF` (`UNRECORDED_FIXTURES` in
 * `support/corpus.ts`). At `DEBUG` a session records the body of every step. Sessions of 8 MiB
 * bodies ran the chart's OpenSearch (512 MiB heap) out of memory, and a sessions search over them
 * overran the 100 MiB response buffer of sessions-management.
 *
 * Shown failing: 12 MiB answers 413 to both cases on the micro engine and passes on the classic one,
 * and an expected buffer with one byte changed fails both cases on both engines.
 */
import { createHash, randomBytes } from "node:crypto";
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { callChain } from "../../support/sessions.js";
import { coversBehavior } from "../../registry/covers.js";

const SIZE = 8 * 1024 * 1024;
const OCTETS = { "Content-Type": "application/octet-stream" };

test.describe("large request bodies", { tag: ["@engine"] }, () => {
  test("the echo returns an 8 MiB body byte for byte", async ({ request, env }) => {
    coversBehavior("http-trigger", "large-body");
    const chain = seedChain(readCorpusState(), "large-body-echo");
    const body = randomBytes(SIZE);

    const call = await callChain(request, env.chainUrl(chain.contextPath), { headers: OCTETS, data: body });

    expect(call.response.status()).toBe(200);
    const echoed = await call.response.body();
    expect(echoed.length).toBe(SIZE);
    // A failed toEqual on two 8 MiB buffers would print both of them.
    expect(echoed.equals(body), "the echoed bytes equal the sent ones").toBe(true);
  });

  test("a script reads every byte of an 8 MiB body", async ({ request, env }) => {
    coversBehavior("script", "large-body");
    const chain = seedChain(readCorpusState(), "script-large-body.yaml");
    const body = randomBytes(SIZE);

    const call = await callChain(request, env.chainUrl(chain.contextPath), { headers: OCTETS, data: body });

    expect(call.response.status()).toBe(200);
    expect(JSON.parse(await call.response.text())).toEqual({
      length: SIZE,
      sha256: createHash("sha256").update(body).digest("hex"),
    });
  });
});
