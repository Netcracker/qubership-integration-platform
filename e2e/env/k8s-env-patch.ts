/**
 * The JSON patches `K8sEnv.restartWith` and `K8sEnv.restart` send to a platform Deployment.
 *
 * A JSON patch rather than a strategic merge: the chart wires most variables through
 * `configMapKeyRef`, and a strategic merge by `name` keeps `valueFrom` beside the new `value`, an
 * entry the API server rejects.
 */

/** One entry of a container's `env`, as the Deployment holds it. */
export interface EnvVar {
  name: string;
  value?: string;
  valueFrom?: unknown;
}

export interface PatchOperation {
  op: "add" | "replace";
  path: string;
  value: unknown;
}

const RESTARTED_AT = "kubectl.kubernetes.io/restartedAt";

function envPath(container: number): string {
  return `/spec/template/spec/containers/${container}/env`;
}

/**
 * Sets each variable in `settings` on the container at index `container`: `add` for a name the
 * container does not declare, `replace` of the whole entry for one it does.
 */
export function settingsPatch(
  container: number,
  current: readonly EnvVar[],
  settings: Record<string, string>,
): PatchOperation[] {
  return Object.entries(settings).map(([name, value]) => {
    const at = current.findIndex((each) => each.name === name);
    return at === -1
      ? { op: "add", path: `${envPath(container)}/-`, value: { name, value } }
      : { op: "replace", path: `${envPath(container)}/${at}`, value: { name, value } };
  });
}

/**
 * Stamps the pod template the way `kubectl rollout restart` does, so the patch restarts the pod
 * even when it changes no variable.
 *
 * `annotations` is the pod template's current map, or `undefined` when it has none: an `add` of
 * the whole map would drop the annotations the chart sets, such as the sidecar opt-out.
 */
export function restartStamp(
  annotations: Record<string, string> | undefined,
  now: string,
): PatchOperation {
  return annotations
    ? {
        op: "add",
        path: `/spec/template/metadata/annotations/${RESTARTED_AT.replace("/", "~1")}`,
        value: now,
      }
    : { op: "add", path: "/spec/template/metadata/annotations", value: { [RESTARTED_AT]: now } };
}

/** Puts back the container's `env` as the Helm manifest declares it. */
export function restorePatch(container: number, declared: readonly EnvVar[]): PatchOperation[] {
  return [{ op: "replace", path: envPath(container), value: declared }];
}
