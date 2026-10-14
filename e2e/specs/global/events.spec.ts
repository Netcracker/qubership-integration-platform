/**
 * `event-controller`, whole: one operation, `GET /v1/catalog/events`.
 *
 * This is the change stream the UI polls to keep a chain's deployment badge live. It is **global by
 * construction** and `e2e/AGENTS.md` rule 2 is what puts the file here: `EventService` holds one
 * `CircularFifoQueue<Event>` of 100 entries for the whole catalog, and the answer is a window over
 * it. No parameter narrows the queue to a caller, a chain, or a folder — `lastEventId` moves the
 * window's start and nothing else — so "the window holds one event" is a claim about every worker
 * on the stack, and it is only true where nothing else is deploying.
 *
 * The spec needs a deployed chain and no `seed` dependency of its own: `global` already declares
 * `api`, `runtime` and `env`, and `runtime` declares `seed`, which Playwright resolves
 * transitively. The chain is built in the worker folder by this spec rather than seeded, because a
 * deployment **event** is published on a state *delta*: the corpus is deployed before this project
 * starts and stays deployed, so borrowing a corpus chain would mean undeploying one every other
 * spec asserts over. A chain of this spec's own costs a create and a snapshot and disturbs nothing.
 *
 * Six shapes measured against the stack rather than assumed:
 *
 * - **only a deployment publishes anything.** `EventService` publishes for three `ObjectType`s and
 *   two of them have no publisher left in this build: `addMessageEvent` is reached only through
 *   `DeploymentService.messagesCallback`, which nothing ever invokes, and the `ENGINE` events go
 *   through `EngineService.enginesCallbacks`, a map that is written by `subscribeEngines` and read
 *   by nobody. `grep -rn enginesCallbacks runtime-catalog/src` and `grep -rn messagesCallback
 *   runtime-catalog/src` return the declaration and the subscribe, and no call site. So `DEPLOYMENT`
 *   is the whole of the live stream, and "a CRUD change publishes nothing" is the
 *   consequence rather than a separate fact.
 * - **the delta, not the request, is what publishes.** `RuntimeDeploymentService.provideEnginesStateUpdate`
 *   diffs what the engines report against what they reported last time and publishes one event per
 *   changed `host + deploymentId`, so the event arrives on the engine's own reporting tick (~2 s
 *   measured) rather than in the `POST .../deployments` response.
 * - **the deploy event carries `userId`, the undeploy event does not.** The publisher reads the
 *   `catalog.deployments` row for the id to learn who created it; after an undeploy that row is
 *   gone, so `userId` and `createdWhen` are both absent from the `REMOVED` event. Absent, not null:
 *   the catalog serializes non-null only.
 * - **the window is capped by age and the cursor does not lift the cap.** `EVENT_TIME_THRESHOLD_MS`
 *   is 15 s and the filter is applied to every event the cursor selected, so an unchanged cursor
 *   goes from one event to none as that event turns 15 s old. Measured at 15.1 s, twice.
 * - **`lastEventId` is the queue's tail, not the last row of the answer.** It is read after the
 *   window filter and names an event the answer may have dropped, which is what the ageing case
 *   asserts.
 * - **a cursor the queue no longer holds loses the window silently.** `getEvents` scans for the id
 *   and only starts collecting once it has found it, so an unknown `lastEventId` answers `200` with
 *   an empty list and the current tail — never a 404, and never a hint that anything was missed.
 *
 * **One filter is deliberately not covered, and cannot be here.** An event is withheld from a
 * caller whose id is not the event's `userId`. `AuditorProvider.localAuditor` answers
 * `new User("0", "developer")` for *every* request on this stack — it is `@ConditionalOnMissingBean`
 * and nothing else supplies one under the `development` profile — so every caller is user `0` and
 * every event this suite can publish is either user `0`'s or nobody's. There is no second identity
 * to assert the filter with, and manufacturing one would mean standing up the security stack the
 * local Compose profile deliberately leaves out. Recorded here rather than asserted weakly.
 */
import { test, expect } from "../../support/fixtures.js";
import { ABSENT_UUID } from "../../support/absent.js";
import { DEPLOY_TIMEOUT, ENGINE_CASE_TIMEOUT } from "../../support/corpus.js";
import { DEFAULT_DOMAIN } from "../../support/catalog.js";
import { tokenized } from "../../support/run.js";
import type { Catalog, CatalogEvent, DeploymentEventData } from "../../support/catalog.js";

/** `EventService.EVENT_TIME_THRESHOLD_MS`. Nothing in the API reports it, so it is spelled here. */
const THRESHOLD_MS = 15_000;

/** A chain of this spec's own: one HTTP trigger, enough to snapshot and to deploy. */
interface OwnChain {
  id: string;
  name: string;
}

async function deployableChain(catalog: Catalog, run: string, folderId: string, what: string): Promise<OwnChain> {
  const name = tokenized(run, `events-${what}`);
  const chain = await catalog.createChain(name, folderId);
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  await catalog.patchElementProperties(chain.id, trigger.id, {
    contextPath: name,
    httpMethodRestrict: "POST",
    externalRoute: false,
  });
  return { id: chain.id, name };
}

/** The event's `data`, narrowed. Every event this stack publishes is a `DEPLOYMENT` one. */
function deploymentData(event: CatalogEvent): DeploymentEventData {
  expect(event.objectType, "an event of a type nothing on this stack publishes").toBe("DEPLOYMENT");
  return event.data as DeploymentEventData;
}

/**
 * Waits for the chain's own event to appear after `cursor`, and answers it.
 *
 * The cursor is held fixed across the poll rather than advanced: the event is published on the
 * engine's reporting tick, and advancing the cursor on an empty answer would move it past the
 * event the moment one arrived between two reads.
 */
async function untilPublished(
  catalog: Catalog,
  cursor: string,
  chainId: string,
  status: string,
): Promise<CatalogEvent> {
  let found: CatalogEvent | undefined;
  await expect
    .poll(
      async () => {
        const update = await catalog.events(cursor);
        found = update.events.find((event) => {
          const data = event.data as Partial<DeploymentEventData>;
          return data?.chainId === chainId && data?.state?.status === status;
        });
        return found !== undefined;
      },
      {
        timeout: DEPLOY_TIMEOUT,
        message: `no ${status} event for chain ${chainId} was ever published`,
      },
    )
    .toBe(true);
  return found!;
}

/**
 * Waits until the window holds nothing at all.
 *
 * Reachable rather than hopeful: events are published on a deployment state delta, the corpus is
 * deployed before this project starts and stays deployed, and `global` runs one worker — so the
 * only thing that can keep the window occupied is an earlier case of this project, and its events
 * age out in 15 s. That bound is what the timeout is sized on.
 */
async function untilEventWindowDrains(catalog: Catalog): Promise<void> {
  await expect
    .poll(async () => (await catalog.events()).events.length, {
      timeout: THRESHOLD_MS + 15_000,
      intervals: [500],
      message: "the event window never drained, so nothing here could claim an empty one",
    })
    .toBe(0);
}

test("a deployment and an undeployment each publish one event naming the chain and its new state", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = await deployableChain(catalog, run, folder.id, "published");
  const snapshot = await catalog.createSnapshot(chain.id);
  const host = (await catalog.engineHosts())[DEFAULT_DOMAIN][0];

  const beforeDeploy = (await catalog.events()).lastEventId;
  const deployment = await catalog.deploy(chain.id, snapshot.id);

  const deployed = await untilPublished(catalog, beforeDeploy, chain.id, "DEPLOYED");
  // Enough to identify what changed without a second call: which deployment, of which chain, from
  // which snapshot, on which engine, into which state. A UI badge is drawn from this alone.
  expect(deploymentData(deployed)).toMatchObject({
    id: deployment.id,
    chainId: chain.id,
    chainName: chain.name,
    snapshotId: snapshot.id,
    domain: DEFAULT_DOMAIN,
    engineHost: host,
    state: { status: "DEPLOYED", suspended: false },
  });
  // Set from the `catalog.deployments` row the publisher looked up, which is also what makes its
  // absence on the undeploy below a fact about the row rather than about the event.
  expect(deploymentData(deployed).createdWhen).toEqual(expect.any(Number));
  expect(deployed.userId, "the deploy event was published for nobody").toBe("0");
  expect(deployed.time).toBeGreaterThan(0);
  // The engine reports one pod, so one delta and one event. A second one would mean the state was
  // reported as changing twice for a single deployment.
  const mine = (await catalog.events(beforeDeploy)).events.filter(
    (event) => (event.data as Partial<DeploymentEventData>)?.chainId === chain.id,
  );
  expect(mine.map((event) => event.id)).toEqual([deployed.id]);

  const beforeUndeploy = (await catalog.events()).lastEventId;
  await catalog.undeploy(chain.id, deployment.id);

  const removed = await untilPublished(catalog, beforeUndeploy, chain.id, "REMOVED");
  expect(deploymentData(removed)).toMatchObject({
    id: deployment.id,
    chainId: chain.id,
    chainName: chain.name,
    snapshotId: snapshot.id,
    state: { status: "REMOVED", suspended: false },
  });
  // Both are read off the deployment row, and the undeploy removed it — so the event that says a
  // deployment is gone cannot say who made it or when. Absent rather than null.
  expect(removed.userId, "the REMOVED event named a user the row no longer holds").toBeUndefined();
  expect(deploymentData(removed).createdWhen).toBeUndefined();
  expect(removed.id).not.toBe(deployed.id);
});

test("a chain's whole CRUD lifecycle publishes nothing, and the deployment that follows publishes one", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  await untilEventWindowDrains(catalog);

  // Every write the catalog offers on a chain short of deploying it: a create, an element create, a
  // property patch, a rename, an element delete, a snapshot. The UI redraws its tree from the
  // response of each rather than from the stream, which is the design this asserts.
  const chain = await deployableChain(catalog, run, folder.id, "crud");
  await catalog.updateChain(chain.id, `${chain.name}-renamed`);
  const spare = await catalog.createElement(chain.id, "script");
  await catalog.deleteElement(chain.id, spare.id);
  const snapshot = await catalog.createSnapshot(chain.id);

  // The negative. It is worth something only because the window was drained first and because the
  // deploy below then fills it: an empty window on its own is also what a broken endpoint answers.
  expect(
    (await catalog.events()).events,
    "a catalog write published an event, which no ObjectType in EventService covers",
  ).toEqual([]);

  const deployment = await catalog.deploy(chain.id, snapshot.id);
  const published = await untilPublished(catalog, "", chain.id, "DEPLOYED");

  // The whole window, not this chain's rows: after a drain and a lifecycle that published nothing,
  // one deploy accounts for every event on the stack.
  const window = (await catalog.events()).events;
  expect(window.map((event) => event.id)).toEqual([published.id]);
  expect(deploymentData(window[0]).id).toBe(deployment.id);

  // Waited for, not fired and forgotten: the undeploy publishes a REMOVED on the engine's next
  // reporting tick, and a case that ends before it lands leaves the event to arrive inside the next
  // case's window — where every assertion is over the queue as a whole.
  const beforeUndeploy = (await catalog.events()).lastEventId;
  await catalog.undeploy(chain.id, deployment.id);
  await untilPublished(catalog, beforeUndeploy, chain.id, "REMOVED");
});

test("the window is capped by the event's age, and an unchanged cursor does not lift the cap", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = await deployableChain(catalog, run, folder.id, "threshold");
  const snapshot = await catalog.createSnapshot(chain.id);

  const cursor = (await catalog.events()).lastEventId;
  const deployment = await catalog.deploy(chain.id, snapshot.id);
  const published = await untilPublished(catalog, cursor, chain.id, "DEPLOYED");

  // The same cursor for every read below. Nothing about the request changes as the answer goes from
  // one event to none, which is the point: paging would need a new cursor to move the window, and
  // this window moves on its own.
  // Seeded with the age at the read `untilPublished` has already made, which saw the event listed.
  // Without it a poll whose first read misses the event leaves `ages` empty, and `Math.max` of
  // nothing is `-Infinity` — reported as "the event left the window far too early" with no reading
  // behind the sentence.
  const ages: number[] = [Date.now() - published.time];
  let droppedAt = -1;
  let tail = "";
  await expect
    .poll(
      async () => {
        const at = Date.now() - published.time;
        const update = await catalog.events(cursor);
        const listed = update.events.some((event) => event.id === published.id);
        if (listed) ages.push(at);
        else droppedAt = Date.now() - published.time;
        tail = update.lastEventId;
        return listed;
      },
      {
        timeout: THRESHOLD_MS + 15_000,
        intervals: [500],
        message: "the event never left the window, so the age cap was never observed",
      },
    )
    .toBe(false);

  // It survived well past the point a paging answer would have moved on, and it was gone at the
  // threshold. Both readings are taken on the safe side of the comparison the catalog makes:
  // `ages` before its request and `droppedAt` after, so neither can be flattered by request latency.
  expect(Math.max(...ages), "the event left the window far too early to be the 15 s cap").toBeGreaterThan(10_000);
  expect(droppedAt, "the event left the window before the 15 s threshold").toBeGreaterThanOrEqual(THRESHOLD_MS);
  // And the cursor is what proves the drop was by age. `lastEventId` still names the event the
  // answer no longer carries — the tail of the queue, read after the window filter — so a client
  // polling with it can never recover what it aged past.
  expect(tail, "the queue's tail moved, so something other than age emptied the window").toBe(published.id);

  // The REMOVED is waited for here too, for the reason the case above it states.
  const beforeUndeploy = (await catalog.events()).lastEventId;
  await catalog.undeploy(chain.id, deployment.id);
  await untilPublished(catalog, beforeUndeploy, chain.id, "REMOVED");
});

test("the cursor forms: omitted, blank, the tail, and one the queue no longer holds", { tag: ["@catalog", "@engine", "@tier2"] }, async ({ catalog, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  const chain = await deployableChain(catalog, run, folder.id, "cursors");
  const snapshot = await catalog.createSnapshot(chain.id);

  const before = (await catalog.events()).lastEventId;
  const deployment = await catalog.deploy(chain.id, snapshot.id);
  const published = await untilPublished(catalog, before, chain.id, "DEPLOYED");

  // Omitted and blank are the same request: `getEvents` declares `defaultValue = ""` and treats a
  // blank cursor as "everything still inside the window".
  const omitted = await catalog.events();
  const blank = await catalog.events("");
  expect(omitted.events.map((event) => event.id)).toContain(published.id);
  expect(blank.events.map((event) => event.id)).toContain(published.id);
  expect(blank.lastEventId).toBe(omitted.lastEventId);

  // The tail is the steady state a polling client sits in: it has seen everything, so it is served
  // nothing, and the tail it is given back is the one it sent.
  const tail = omitted.lastEventId;
  const caughtUp = await catalog.events(tail);
  expect(caughtUp.events, "an event newer than the tail the same read reported").toEqual([]);
  expect(caughtUp.lastEventId).toBe(tail);

  // And a cursor the queue does not hold is neither refused nor reported: the scan never finds its
  // id, so it collects nothing, and the caller is handed the current tail with no sign that a
  // window it had a right to was skipped. The silence is the contract; there is no error path.
  const stale = await catalog.events(ABSENT_UUID);
  expect(stale.events, "an unknown cursor served events rather than skipping them").toEqual([]);
  expect(stale.lastEventId, "an unknown cursor did not report the queue's tail").toBe(tail);

  // And the same wait, so the last case of the file leaves the window as it found it.
  const beforeUndeploy = (await catalog.events()).lastEventId;
  await catalog.undeploy(chain.id, deployment.id);
  await untilPublished(catalog, beforeUndeploy, chain.id, "REMOVED");
});
