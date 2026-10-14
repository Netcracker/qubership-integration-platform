/** The projects only the Kubernetes target declares, in the order `env/target-setup.ts` declares them. */
export const K8S_PROJECTS = ["k8s", "seed-micro", "runtime-micro", "runtime-micro-redeploy", "global-micro"];

/**
 * The broker projects `E2E_BROKERS=0` drops on either target, written out rather than read off
 * `env/target-setup.ts`, so a spec can hold the setup to them.
 */
export const BROKER_PROJECTS = ["brokers-seed", "brokers", "brokers-restart"];

/** The broker projects `E2E_BROKERS=0` also drops on the Kubernetes target, the only one with a micro engine. */
export const MICRO_BROKER_PROJECTS = ["brokers-micro-seed", "brokers-micro"];
