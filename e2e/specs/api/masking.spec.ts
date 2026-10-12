/**
 * Logging masking: the fields a chain hides from its session trace, and the logging properties that
 * decide whether there is a trace at all.
 *
 * The two families sit in one file because they are one setting split across two stores. A masked
 * field is a row in Postgres hanging off the chain; `maskingEnabled` — the switch that decides
 * whether the engine honours those rows — is one of the logging properties, and those live in
 * Consul. A spec that covered one without the other would pin half a feature.
 *
 * **The logging properties are read through a cache, not from Consul.** The write goes straight to
 * the KV store, but `GET .../properties/logging` answers from a map the catalog refills on a 1 s
 * tick (`TasksScheduler.checkRuntimeDeploymentProperties` into
 * `ChainRuntimePropertiesService.updateCache`). So a read immediately after a write still reports
 * the old state, and every assertion over `custom` here polls. Measured: 83 ms for a delete to
 * become visible on a quiet stack, which is the blocking query returning rather than the tick.
 *
 * **`sessionsLoggingLevel` defaults to `OFF`, and every trace assertion depends on that.** It is
 * the reason a fixture chain records no session unless the level is raised between import and
 * deploy. The default-shape case below is what pins it, so a change to the default surfaces here
 * rather than as an unexplained absence of traces in every runtime spec.
 *
 * Four measured shapes, each an asymmetry a caller would otherwise have to discover:
 *
 * - A second masked field of the same name on one chain answers **409** `Field with name X already
 *   exist` — not 200, and not a 400.
 * - The **single** delete answers 404 for a field id that does not exist; the **batch** delete —
 *   which is a `POST` to `.../masking/field` carrying a bare array — answers **204** for the same
 *   id. So the batch can never tell a caller whether it removed anything.
 * - Every masking call resolves the chain first, so an unknown chain id is a 404 naming the chain.
 * - The logging properties endpoints resolve **nothing**. `GET` on a chain that does not exist
 *   answers 200 with the fallback, and `POST` answers **200 and writes the key**, leaving a Consul
 *   entry under an id no chain will ever have. Pinned below, and cleaned up, because a suite that
 *   discovered this by leaving residue would be repeating the mistake.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, LoggingProperties } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_ID } from "../../support/absent.js";
import { releaseChains } from "../../support/cleanup.js";

/** The compiled-in defaults, `DeploymentRuntimeProperties.DEFAULT_VALUES` as it serializes. */
const FALLBACK_DEFAULT: LoggingProperties = {
  sessionsLoggingLevel: "OFF",
  logLoggingLevel: "ERROR",
  logPayload: ["HEADERS", "PROPERTIES"],
  logPayloadEnabled: false,
  dptEventsEnabled: false,
  maskingEnabled: true,
  sessionLogDetails: "OFF",
};

/**
 * The same properties with `logPayload` sorted, which is how the fallback layer has to be compared.
 *
 * `DeploymentRuntimeProperties.DEFAULT_VALUES` builds it with `Set.of(HEADERS, PROPERTIES)`, and
 * `Set.of` randomises its iteration order once per JVM, so the array serializes as
 * `["HEADERS","PROPERTIES"]` on one boot of the catalog and `["PROPERTIES","HEADERS"]` on the next.
 * Asserting the array as it arrives passes or fails on which container is running, which is a
 * coin toss and not a contract; the membership is the contract.
 */
function sorted(properties: LoggingProperties): LoggingProperties {
  return { ...properties, logPayload: [...properties.logPayload].sort() };
}

/** The custom layer once the catalog's cache has caught up with Consul, or `undefined` when gone. */
async function settledCustom(
  catalog: Catalog,
  chainId: string,
  present: boolean,
): Promise<LoggingProperties | undefined> {
  let custom: LoggingProperties | undefined;
  await expect
    .poll(
      async () => {
        custom = (await catalog.getLoggingProperties(chainId)).custom;
        return custom !== undefined;
      },
      { message: `waiting for the custom logging layer of ${chainId} to be ${present ? "written" : "gone"}` },
    )
    .toBe(present);
  return custom;
}

test("a masked field round-trips through create, list, update, and delete", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "mask-crud"), folder.id);
  expect(await catalog.listMaskedFields(chain.id), "a new chain masks nothing").toEqual([]);

  const name = tokenized(run, "mask-secret");
  const created = await catalog.createMaskedField(chain.id, name);
  expect(created.name).toBe(name);
  expect(created.id).toBeTruthy();
  expect(created.createdWhen).toBeGreaterThan(0);

  expect(await catalog.listMaskedFields(chain.id)).toMatchObject([{ id: created.id, name }]);

  // The name is unique per chain, and the second write says so with a 409 rather than replacing
  // the field or answering the one that is already there.
  const duplicate = await catalog.raw("post", `/v1/chains/${chain.id}/masking`, { name });
  expect(duplicate.status()).toBe(409);
  expect(((await duplicate.json()) as { errorMessage: string }).errorMessage).toBe(
    `Field with name ${name} already exist`,
  );
  expect(await catalog.listMaskedFields(chain.id)).toHaveLength(1);

  const renamed = `${name}-renamed`;
  const updated = await catalog.updateMaskedField(chain.id, created.id, renamed);
  expect(updated).toMatchObject({ id: created.id, name: renamed });
  expect(updated.modifiedWhen).toBeGreaterThanOrEqual(created.createdWhen!);
  expect(await catalog.listMaskedFields(chain.id)).toMatchObject([{ id: created.id, name: renamed }]);

  const deleted = await catalog.raw("delete", `/v1/chains/${chain.id}/masking/field/${created.id}`);
  expect(deleted.status(), "the single delete answers 204").toBe(204);
  expect(await catalog.listMaskedFields(chain.id)).toEqual([]);
});

test("the batch delete removes what it names and reports nothing about what it cannot find", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "mask-batch"), folder.id);
  const first = await catalog.createMaskedField(chain.id, tokenized(run, "mask-batch-1"));
  const second = await catalog.createMaskedField(chain.id, tokenized(run, "mask-batch-2"));
  const third = await catalog.createMaskedField(chain.id, tokenized(run, "mask-batch-3"));

  const removed = await catalog.raw("post", `/v1/chains/${chain.id}/masking/field`, [
    first.id,
    second.id,
  ]);
  expect(removed.status()).toBe(204);
  expect(
    (await catalog.listMaskedFields(chain.id)).map((field) => field.id),
    "the batch removes exactly the ids it names",
  ).toEqual([third.id]);

  // The asymmetry that matters: the same id is a 404 through the single delete and a silent 204
  // through the batch, so a caller cannot use the batch to learn whether anything happened.
  expect((await catalog.raw("delete", `/v1/chains/${chain.id}/masking/field/${first.id}`)).status()).toBe(404);
  expect((await catalog.raw("post", `/v1/chains/${chain.id}/masking/field`, [first.id])).status()).toBe(204);
});

test("masking resolves the chain and the field, and names whichever it cannot find", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "mask-404"), folder.id);

  for (const [method, path] of [
    ["get", `/v1/chains/${ABSENT_ID}/masking`],
    ["post", `/v1/chains/${ABSENT_ID}/masking`],
  ] as const) {
    const response = await catalog.raw(method, path, method === "post" ? { name: "x" } : undefined);
    expect(response.status(), `${method} ${path}`).toBe(404);
    expect(((await response.json()) as { errorMessage: string }).errorMessage).toBe(
      `Can't find chain with id: ${ABSENT_ID}`,
    );
  }

  for (const [method, body] of [
    ["put", { name: "x" }],
    ["delete", undefined],
  ] as const) {
    const response = await catalog.raw(method, `/v1/chains/${chain.id}/masking/field/${ABSENT_ID}`, body);
    expect(response.status(), `${method} on an unknown field`).toBe(404);
    expect(((await response.json()) as { errorMessage: string }).errorMessage).toBe(
      `Can't find masked field with id: ${ABSENT_ID}`,
    );
  }
});

test("a chain reports the fallback logging properties until it has custom ones", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "logging-default"), folder.id);

  const initial = await catalog.getLoggingProperties(chain.id);
  // The whole default layer, field by field. `sessionsLoggingLevel: OFF` is the one the rest of the
  // suite rests on: a fixture chain records no session at all until this is raised, so a change to
  // the default has to fail here rather than as an empty trace in a runtime spec.
  expect(sorted(initial.fallbackDefault)).toEqual(FALLBACK_DEFAULT);
  expect(initial.custom, "a chain with no custom properties omits the key rather than nulling it").toBeUndefined();
});

test("custom logging properties are written, read back, and dropped again", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await catalog.createChain(tokenized(run, "logging-custom"), folder.id);
  let bodyFailed = true;
  const wanted: LoggingProperties = {
    sessionsLoggingLevel: "DEBUG",
    logLoggingLevel: "INFO",
    logPayload: ["HEADERS"],
    logPayloadEnabled: false,
    dptEventsEnabled: false,
    maskingEnabled: false,
    sessionLogDetails: "OFF",
  };

  try {
    // Inside the `try`, because a write that fails after the server has already stored the key
    // still leaves one in Consul, and the `finally` is the only thing that removes it.
    await catalog.saveLoggingProperties(chain.id, wanted);
    // The write lands in Consul and the read comes from a cache the catalog refills on a tick, so
    // this is a poll and not a read. A single read here passes or fails on timing.
    expect(await settledCustom(catalog, chain.id, true)).toEqual(wanted);
    // The fallback layer is unchanged by a custom one: the two are reported side by side so a caller
    // can show what a chain overrides.
    expect(sorted((await catalog.getLoggingProperties(chain.id)).fallbackDefault)).toEqual(FALLBACK_DEFAULT);

    const deleted = await catalog.raw("delete", `/v1/chains/${chain.id}/properties/logging`);
    expect(deleted.status()).toBe(204);
    await settledCustom(catalog, chain.id, false);
    // Deleting properties a chain does not have is a 204 as well, so the endpoint is idempotent and
    // says nothing about whether there was anything to remove.
    expect((await catalog.raw("delete", `/v1/chains/${chain.id}/properties/logging`)).status()).toBe(204);
    bodyFailed = false;
  } finally {
    // `releaseChains` rather than a `deleteLoggingProperties` of this spec's own, and for the reason
    // that module exists: this chain sits in the worker folder, and the `folder` fixture cascades
    // that folder away after the worker's last test. A swallowed delete therefore does not leave a
    // key the next sweep can still find. It leaves one filed under an id no listing reaches, because
    // the chain naming it goes with the cascade. So the key delete goes first, a failed one moves
    // the chain to the root where the cascade cannot take it, and a failed move spends the last
    // moment the key is addressable on a second delete.
    //
    // A no-op on the passing path, where the case has already deleted the key twice. `releaseChains`
    // reads only the id and the name of what it is handed; this chain has no route, and undeploying
    // one that was never deployed answers 204.
    const chains = [{ id: chain.id, name: chain.name }];
    await releaseChains(catalog, chains, bodyFailed);
  }
});

test("a logging properties write for an unknown chain answers 404 and stores nothing", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  // Before #844 the write answered 200 and left a Consul key under an id no chain would ever claim.
  // The read still does not resolve the chain.
  const ghost = tokenized(run, "logging-ghost");
  const witness = await catalog.createChain(tokenized(run, "logging-witness"), folder.id);
  let bodyFailed = true;

  const read = await catalog.getLoggingProperties(ghost);
  expect(sorted(read.fallbackDefault), "an unknown chain reads as the fallback, not as a 404").toEqual(
    FALLBACK_DEFAULT,
  );
  expect(read.custom).toBeUndefined();

  try {
    const written = await catalog.raw("post", `/v1/chains/${ghost}/properties/logging`, {
      sessionsLoggingLevel: "DEBUG",
    });
    expect(written.status(), "the write refuses a chain id nothing answers to").toBe(404);
    // The catalog replaces its whole cache from Consul on each change, so once a later write to a
    // real chain shows up, a key the refused write had left would show up too.
    await catalog.saveLoggingProperties(witness.id, { sessionsLoggingLevel: "DEBUG" });
    await settledCustom(catalog, witness.id, true);
    expect((await catalog.getLoggingProperties(ghost)).custom).toBeUndefined();
    bodyFailed = false;
  } finally {
    // A write that went through anyway leaves a key no sweep can find, so it is removed here.
    await catalog.deleteLoggingProperties(ghost).catch(() => {});
    await releaseChains(catalog, [{ id: witness.id, name: witness.name }], bodyFailed);
  }
});
