/**
 * The ids nothing answers to, in the two shapes the platform needs.
 *
 * Two rather than one, and the difference is measured: most services look the id up and echo it
 * back in the message, so a readable string keeps a failed assertion readable; the testing service
 * parses its path variable as a UUID **before** it looks anything up, so a non-UUID there answers
 * 400 and never reaches the lookup the case is about.
 *
 * Nothing is ever created under either, so neither can collide and neither needs the run token.
 *
 * In `support/` rather than in `specs/api/constants.ts`, which is where they lived while only the
 * `api` project asked for one: `specs/runtime/composition.spec.ts` needs an element id no chain
 * holds, and a fourth private spelling is what this file exists to stop.
 */

/** An id no entity has, readable, for a service that echoes it back in its error message. */
export const ABSENT_ID = "e2e-absent-id";

/** The same idea in UUID shape, for a service that parses the id before it looks anything up. */
export const ABSENT_UUID = "00000000-0000-0000-0000-000000000000";

/** A lowercase UUID, unanchored, for a pattern that embeds one. */
export const UUID_TEXT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

/** The shape of an id the testing service mints, lowercase as it writes one. */
export const UUID = new RegExp(`^${UUID_TEXT}$`);
