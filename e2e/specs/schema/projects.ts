/**
 * The Compose projects `E2E_BROKERS=0` drops, written out rather than read off
 * `env/target-setup.ts`, so a spec can hold the setup to them.
 */
export const BROKER_PROJECTS = ["brokers-seed", "brokers-seed-teardown", "brokers", "brokers-restart"];
