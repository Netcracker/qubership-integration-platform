/**
 * The micro half of `specs/api/creation-path-equivalence.spec.ts`: a chain built through the API and
 * the same chain restored from its own export run identically on the micro engine.
 *
 * Every deploy rolls a micro pod, so the case runs here and never in the shared `seed-micro` domain.
 * The API-built chain runs in one domain, and the imported chain in a second one, created after the
 * first is deleted. A redeploy to the same domain would leave the old pod serving until the new one
 * is ready, so a route that answered could still be the original chain's.
 */
import { test, expect } from "../../support/fixtures.js";
import type { ImportResult } from "../../support/catalog.js";
import { releaseChains } from "../../support/cleanup.js";
import { MICRO_READY_TIMEOUT, SEED_LOGGING } from "../../support/corpus.js";
import {
  createDeployableChain,
  deleteDomain,
  deployToDomain,
  tokenedDomain,
  type DeployableChain,
  type TokenedDomain,
} from "../../support/deployable.js";
import { drive, expectSameRun, RECORDED_STEPS } from "../../support/equivalence.js";
import { tokenized } from "../../support/run.js";

/** The domain create and the route poll, plus building the chain. */
const SETUP_TIMEOUT = MICRO_READY_TIMEOUT + 60_000;

/** A second domain's create and route poll, plus the export, the import, and two driven calls. */
const CASE_TIMEOUT = MICRO_READY_TIMEOUT + 120_000;

let built: DeployableChain | undefined;
/** The domain that runs the chain right now, which `afterAll` deletes. */
let live: TokenedDomain | undefined;

test.beforeAll(async ({ catalog, folder, run, microDomainState }) => {
  test.setTimeout(SETUP_TIMEOUT);
  const name = tokenized(run, "equivalence");
  built = await createDeployableChain(catalog, { name, parentId: folder.id, contextPath: name, marker: "built" });
  // Before the deploy, as in the classic half: the engine records nothing at the default level.
  await catalog.saveLoggingProperties(built.id, SEED_LOGGING);
  live = tokenedDomain(run, "equivalence-api", microDomainState);
  await deployToDomain(catalog, live, [built]);
});

test.afterAll(async ({ catalog }) => {
  if (live) await deleteDomain(catalog, live);
  if (!built) return;
  await releaseChains(catalog, [built], false);
  // After the import the chain sits at the root, where the worker folder cascade never reaches it.
  await catalog.deleteChain(built.id);
});

test("a chain from its own export runs on the micro engine the same way the API-built original did", { tag: ["@catalog", "@engine", "@sessions", "@tier1"] }, async ({ catalog, request, run, sessions, microDomainState }) => {
  test.setTimeout(CASE_TIMEOUT);
  const chain = built as DeployableChain;
  const fromApiDomain = live as TokenedDomain;

  const fromApi = await drive(request, fromApiDomain.env, sessions, chain, RECORDED_STEPS);
  expect(fromApi.status).toBe(200);
  expect(fromApi.steps.length, "the API-built chain recorded a trace of another shape").toBe(RECORDED_STEPS);

  // The bytes the exporter wrote, imported unchanged (rule 6).
  const archive = await catalog.exportChain(chain.id);
  await deleteDomain(catalog, fromApiDomain);
  live = undefined;

  // Delete before importing, so the import creates the chain rather than updating it (rule 5).
  await catalog.deleteChain(chain.id);
  expect((await catalog.raw("get", `/v1/chains/${chain.id}`)).status()).toBe(404);
  const response = await catalog.importChains(archive, `${chain.name}.zip`);
  expect(response.status(), await response.text()).toBe(200);
  const rows = ((await response.json()) as ImportResult).chains ?? [];
  expect(rows.map((each) => `${each.name} ${each.status}`)).toEqual([`${chain.name} CREATED`]);
  expect(rows[0].id).toBe(chain.id);

  await catalog.saveLoggingProperties(chain.id, SEED_LOGGING);
  live = tokenedDomain(run, "equivalence-import", microDomainState);
  await deployToDomain(catalog, live, [chain]);
  const fromDocument = await drive(request, live.env, sessions, chain, fromApi.steps.length);

  expectSameRun(fromDocument, fromApi);
});
