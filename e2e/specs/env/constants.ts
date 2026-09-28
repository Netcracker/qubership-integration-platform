/**
 * What the specs in this project agree on, which is the cost of the thing that defines it.
 *
 * `specs/env/` is the project whose members restart a service, and a restart is the reason none of
 * them fits the suite's 120 s default. Two files declared the budget themselves and disagreed about
 * it — 300 s against 240 s — over docstrings that said the same thing.
 *
 * The file is not a spec and Playwright's default `testMatch` does not collect it, so it stays a
 * module the specs import.
 */

/**
 * How long a case that restarts a service gets.
 *
 * A restart is about 30 s of container recreate plus a health wait, and a case rarely does only
 * one: `service-type-roundtrip` restarts the catalog twice, and `restart-resilience` follows its
 * restart with a poll of the whole corpus's routes. Five minutes is the wider of the two numbers
 * the specs used to carry, and nothing on this stack has come near it.
 */
export const RESTART_TIMEOUT = 300_000;
