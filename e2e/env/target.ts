/**
 * The infrastructure a run targets, read from `CIP_TARGET`.
 *
 * This module imports nothing, so `playwright.config.ts`, `env/containers.ts`, and the support
 * modules can read the target without loading an adapter. `registry/reconcile.mjs` reaches it under
 * `node --experimental-strip-types` through `env/containers.ts`, so an import added here has to name
 * a `.ts` file.
 */

export type Target = "compose" | "k8s";

const TARGETS: readonly Target[] = ["compose", "k8s"];

/** `compose` when `CIP_TARGET` is unset. Any other value fails the run at config load. */
export function target(): Target {
  const raw = process.env.CIP_TARGET ?? "compose";
  if ((TARGETS as readonly string[]).includes(raw)) return raw as Target;
  throw new Error(
    `CIP_TARGET must be "compose" or "k8s", got ${JSON.stringify(raw)}. Unset it to run against ` +
      `Docker Compose.`,
  );
}

/**
 * The name of a state file on the current target: `.e2e-corpus.json` on Compose and
 * `.e2e-k8s-corpus.json` on Kubernetes.
 *
 * The files carry no target inside them, so a shared name would let a Compose run's residue be swept
 * or reverted against the cluster. Compose keeps the names it always had, so no existing residue is
 * orphaned.
 */
export function stateFileName(name: string): string {
  return target() === "compose" ? name : name.replace(/^\.e2e-/, ".e2e-k8s-");
}
