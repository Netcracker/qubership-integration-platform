/**
 * The floors two specs hold the element library against, and the sizes they were measured from.
 *
 * A floor rather than an equality, because the palette grows with every element the platform ships
 * and a spec pinning today's number would go red on a feature. What a floor is for is vacuity:
 * `[].filter()` is `[]`, so a partition that came back empty satisfies every rule assertion below
 * it while proving nothing about the rule.
 *
 * Measured against `GET /v1/library` on this stack: 55 elements across the groups at every depth,
 * 23 child elements, and 78 distinct names across the two. Each floor sits a few below what it
 * measured, which is how far the palette may shrink before the number is worth re-reading.
 *
 * Here rather than in `specs/api/constants.ts`, for the reason that file states about itself: it
 * holds what only `specs/api/` reads, and `support/` is where a constant moves once a sibling
 * project needs it. `specs/global/element-library-endpoints.spec.ts` is that sibling.
 */

/** Elements reached through the groups, at every depth. Measured: 55. */
export const GROUPED_ELEMENT_FLOOR = 50;

/** Entries in the root's `childElements` map. Measured: 23. */
export const CHILD_ELEMENT_FLOOR = 20;

/** Distinct names across both partitions, which is the whole palette. Measured: 78. */
export const PALETTE_FLOOR = 70;
