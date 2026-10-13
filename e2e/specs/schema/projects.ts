/**
 * The broker projects `E2E_BROKERS=0` drops on either target, written out rather than read off
 * `env/target-setup.ts`, so a spec can hold the setup to them.
 */
export const BROKER_PROJECTS = ["brokers-seed", "brokers", "brokers-restart"];
