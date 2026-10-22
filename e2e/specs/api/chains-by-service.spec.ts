/**
 * The five readers of `chain-controller` that find the chains calling a service.
 *
 * Each reads the ids a `service-call` element carries in its properties, so no specification has
 * to be imported behind them: the case writes the operation tab's fields the way the UI does on
 * choosing an operation, as `specs/api/services.spec.ts` does for the usage readers.
 *
 * `GET /v1/chains/systems/{systemId}` and `GET /v1/chains/{systemId}/specificationGroup` match on
 * the service id, read off `ElementHelperService`. The other three match the operation,
 * specification, or group id alone and ignore the service id in their path. The ids the catalog
 * mints start with the service id, so on real data both readings select the same chains.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, ChainOfService } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_ID } from "../../support/absent.js";

/** The ids a `service-call` names, minted per case and per call. */
interface CalledOperation {
  groupId: string;
  modelId: string;
  operationId: string;
}

function operationOf(run: string, systemId: string, what: string): CalledOperation {
  const groupId = `${systemId}-${tokenized(run, `by-service-${what}`)}`;
  const modelId = `${groupId}-1.0.0`;
  return { groupId, modelId, operationId: `${modelId}-${what}` };
}

/** A chain in the worker folder with one `service-call` naming the operation. */
async function callerOf(
  catalog: Catalog,
  run: string,
  parentId: string,
  systemId: string,
  operation: CalledOperation,
  what: string,
): Promise<{ id: string; elementId: string }> {
  const chain = await catalog.createChain(tokenized(run, `by-service-${what}`), parentId);
  const element = await catalog.createElement(chain.id, "service-call");
  await catalog.patchElementProperties(chain.id, element.id, {
    systemType: "EXTERNAL",
    integrationSystemId: systemId,
    integrationSpecificationGroupId: operation.groupId,
    integrationSpecificationId: operation.modelId,
    integrationOperationId: operation.operationId,
    integrationOperationPath: "/orders",
    integrationOperationMethod: "POST",
  });
  return { id: chain.id, elementId: element.id };
}

const idsOf = (chains: ChainOfService[]) => chains.map((each) => each.id).sort();

test.describe("chains of a service", { tag: ["@catalog"] }, () => {
  test("the five readers find the chains calling a service", async ({ catalog, folder, run }) => {
    const system = await catalog.createSystem(tokenized(run, "by-service"), "EXTERNAL");
    const first = operationOf(run, system.id, "first");
    const second = operationOf(run, system.id, "second");
    // A chain calling another service, so the service reader is seen to filter.
    const elsewhereId = tokenized(run, "by-service-elsewhere");
    const elsewhere = operationOf(run, elsewhereId, "elsewhere");
    const chains: string[] = [];
    try {
      const { one, two } = await test.step("three chains call two services", async () => {
        const one = await callerOf(catalog, run, folder.id, system.id, first, "one");
        chains.push(one.id);
        const two = await callerOf(catalog, run, folder.id, system.id, second, "two");
        chains.push(two.id);
        const other = await callerOf(catalog, run, folder.id, elsewhereId, elsewhere, "other");
        chains.push(other.id);
        return { one, two };
      });

      await test.step("the service reader finds its two chains", async () => {
        const light = await catalog.chainsOfSystem(system.id);
        expect(idsOf(light)).toEqual([one.id, two.id].sort());
        expect(light.every((chain) => chain.elements === undefined), "the default mode leaves the elements out").toBe(true);

        const full = await catalog.chainsOfSystem(system.id, "full");
        expect(idsOf(full)).toEqual([one.id, two.id].sort());
        expect(full.find((chain) => chain.id === one.id)?.elements?.map((each) => each.id)).toEqual([one.elementId]);
      });

      await test.step("the operation, specification, and group readers find one chain each", async () => {
        expect(idsOf(await catalog.chainsOfOperation(system.id, first.operationId))).toEqual([one.id]);
        expect(idsOf(await catalog.chainsOfSpecification(system.id, second.modelId))).toEqual([two.id]);
        expect(idsOf(await catalog.chainsOfSpecificationGroup(system.id, first.groupId))).toEqual([one.id]);
      });

      await test.step("the grouped reader keys each chain by its group", async () => {
        const grouped = await catalog.chainsBySpecificationGroup(system.id);
        expect(
          grouped
            .map((row) => ({ specificationGroupId: row.specificationGroupId, chains: idsOf(row.chains) }))
            .sort((a, b) => a.specificationGroupId.localeCompare(b.specificationGroupId)),
        ).toEqual(
          [
            { specificationGroupId: first.groupId, chains: [one.id] },
            { specificationGroupId: second.groupId, chains: [two.id] },
          ].sort((a, b) => a.specificationGroupId.localeCompare(b.specificationGroupId)),
        );
      });
    } finally {
      // The service cannot be deleted while a chain references it.
      for (const id of chains) await catalog.deleteChain(id).catch(() => {});
      await catalog.deleteSystem(system.id).catch(() => {});
    }
  });

  test("an id nothing names finds no chain, not a 404", async ({ catalog }) => {
    for (const path of [
      `/v1/chains/systems/${ABSENT_ID}`,
      `/v1/chains/${ABSENT_ID}/${ABSENT_ID}`,
      `/v1/chains/${ABSENT_ID}/model/${ABSENT_ID}`,
      `/v1/chains/${ABSENT_ID}/specificationGroup/${ABSENT_ID}`,
      `/v1/chains/${ABSENT_ID}/specificationGroup`,
    ]) {
      const response = await catalog.raw("get", path);
      expect(response.status(), path).toBe(200);
      expect(await response.json(), path).toEqual([]);
    }
  });
});
