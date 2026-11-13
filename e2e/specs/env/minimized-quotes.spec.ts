/**
 * Chain and service export with minimized quotes, which `CIP_EXPORT_MINIMIZE_QUOTES=true` turns on
 * (#963).
 *
 * The setting is off by default, so the case restarts the catalog with it on and belongs to the
 * `env` project. `afterAll` restarts the catalog with the committed settings, and a run killed
 * before that is recovered by provisioning, which reads `e2e/.e2e-overrides.json`. Common variables
 * export with minimized quotes whatever the setting, and `specs/api/import-export.spec.ts` covers
 * them on the default stack.
 *
 * Shown failing: on the catalog of 1bc09893a, `plain text` is exported quoted. On 3fe825ed3 the
 * same assertion fails with the setting left at its default.
 */
import { test, expect } from "../../support/fixtures.js";
import { tokenized } from "../../support/run.js";
import { tokenizedChain } from "../../support/deployable.js";
import { entryText } from "../../support/zip.js";
import { chainEntry, importChains } from "../../support/chain-import.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { expectImportedServices } from "../../support/service-import.js";
import { RESTART_TIMEOUT } from "./constants.js";

/** Whether the catalog runs with the setting on, and therefore whether `afterAll` restores it. */
let minimizeQuotesOn = false;

test.describe("minimized-quote export", { tag: ["@catalog"] }, () => {
  test.beforeAll(async ({ env }) => {
    const settings = await env.settings("runtime-catalog");
    expect(
      settings.CIP_EXPORT_MINIMIZE_QUOTES,
      "the catalog already runs with minimized quotes; this spec sets and clears the setting itself " +
        "and would leave the container in a state it did not find it in",
    ).not.toBe("true");
  });

  test.afterAll(async ({ env }) => {
    test.setTimeout(RESTART_TIMEOUT);
    if (minimizeQuotesOn) await env.restart("runtime-catalog");
  });

  // The export writes `plain text` unquoted and quotes every string that YAML would read back as
  // another type.
  test("number-like strings survive a round trip", async ({ catalog, env, folder, run }) => {
    test.setTimeout(RESTART_TIMEOUT);
    // Set before the await: a throw in the health wait of `restartWith` would otherwise leave the
    // setting on while `afterAll` found nothing to restore.
    minimizeQuotesOn = true;
    await env.restartWith("runtime-catalog", { CIP_EXPORT_MINIMIZE_QUOTES: "true" });

    const headers = {
      "X-Decimal": "1.10",
      "X-Underscored": "1_000",
      "X-Hex": "0x1F",
      "X-Infinity": ".inf",
      "X-Plain": "plain text",
    };
    const chain = await tokenizedChain(catalog, run, { prefix: "export", what: "quotes", parentId: folder.id });
    await catalog.patchElementProperties(chain.id, chain.headerElementId, { headerModificationToAdd: headers });

    const service = await catalog.createSystem(tokenized(run, "quotes-service"), "EXTERNAL");
    const groupName = tokenized(run, "quotes-group");
    const modelId = `${service.id}-${groupName}-1.10`;

    try {
      await test.step("create the service", async () => {
        const fixture = readSpecificationFixture("widgets.openapi.yaml");
        const source = fixture.buffer.toString("utf-8").replace("version: 1.0.0", 'version: "1.10"');
        await catalog.awaitSpecificationImport(
          await catalog.importSpecificationGroup(service.id, groupName, {
            ...fixture,
            buffer: Buffer.from(source),
          }),
        );
        expect((await catalog.getModel(modelId)).version).toBe("1.10");
      });

      const { chains, services } = await test.step("export, then delete", async () => {
        const chains = await catalog.exportChains([chain.id]);
        // Soft, so that a catalog that quotes everything still runs the imports below.
        expect.soft(await entryText(chains, chainEntry(chain.id))).toMatch(/^\s*X-Plain: plain text$/m);
        const services = await catalog.exportSystems([service.id]);

        await catalog.deleteChain(chain.id);
        await catalog.deleteSystem(service.id);
        return { chains, services };
      });

      await test.step("the chain imports with its header values", async () => {
        const imported = await importChains(catalog, chains);
        expect(imported.body.chains).toEqual([expect.objectContaining({ id: chain.id, status: "CREATED" })]);
        const header = (await catalog.listChainElements(chain.id)).find((each) => each.type === "header-modification");
        expect(header?.properties?.headerModificationToAdd).toEqual(headers);
      });

      await test.step("the service imports with its specification version", async () => {
        await expectImportedServices(await catalog.importArchive("/v1/import/system", services, "services.zip"), [
          { id: service.id },
        ]);
        expect((await catalog.getModel(modelId)).version).toBe("1.10");
      });
    } finally {
      await catalog.deleteSystem(service.id).catch(() => {});
    }
  });
});
