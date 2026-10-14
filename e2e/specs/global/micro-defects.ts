/**
 * The defects the `global-micro` cases meet on the micro engine.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import type { ConditionalDefect } from "../../support/known-defect.js";

const NOTHING_LISTED = /no live exchange for chain \S+ was ever listed|the engine never reported an exchange for /;
const HELD_ANSWER = /the held call answered 200 \{"element":"long-running","heldMs":\d+\}/;

/**
 * The micro engine lists no live exchange. Measured on the shared micro domain: a call the
 * `long-running` copy held for 8 s answered 200 with its `heldMs`, while
 * `GET /v1/engine/live-exchanges` on the pod answered 204 throughout. `application.yml` sets
 * `inflightRepositoryBrowseEnabled` under `camel.main.globalOptions` rather than on `camel.main`, which
 * would leave `InflightRepository.browse` empty (unverified); the classic engine enables browsing in
 * `IntegrationRuntimeService`. No issue is filed yet.
 */
export const LIVE_EXCHANGE_UNLISTED: ConditionalDefect = {
  title: "the micro engine lists no live exchange while a call is in flight",
  matches: (message) => NOTHING_LISTED.test(message) && HELD_ANSWER.test(message),
};

/**
 * The micro engine answers a kill for an unknown deployment with the message of an unknown exchange.
 * The classic engine names the two 404s apart; the micro one throws
 * `No live exchange found for deployment id <id>` for both. No issue is filed yet.
 */
export const KILL_NOT_FOUND_UNSPECIFIC: ConditionalDefect = {
  title: "the micro engine reports an unknown deployment as an unknown exchange",
  matches: (message) => message.includes("No live exchange found for deployment id no-such-deployment"),
};

/**
 * The two engines answer `limit=0` with different statuses. The micro engine maps the constraint
 * violation to 400, as the catalog does, and the classic engine has no handler and answers 500, which
 * the case pins. No issue is filed yet.
 */
export const LIMIT_REFUSED_WITH_400: ConditionalDefect = {
  title: "the micro engine answers limit=0 with 400 where the classic engine answers 500",
  matches: (message) => message.includes('"title":"Constraint Violation","status":400'),
};
