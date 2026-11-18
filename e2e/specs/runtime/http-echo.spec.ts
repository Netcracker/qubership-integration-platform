import { test } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { registerHttpEchoTests } from "../../suites/http-echo.js";
import type { EchoChain } from "../../suites/types.js";

const runtimeTest = test.extend<{ echo: EchoChain }>({
  echo: async ({ env }, use) => {
    const corpus = readCorpusState();
    const chain = seedChain(corpus, "http-echo");
    await use({
      id: chain.id, url: env.chainUrl(chain.contextPath), marker: corpus.run,
      markerHeader: "e2e-fixture",
    });
  },
});

registerHttpEchoTests(runtimeTest);
