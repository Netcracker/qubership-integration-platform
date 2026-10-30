/**
 * `awaitBootstrap` and `readyCluster`, which hold the provisioner's first cluster call until the
 * bootstrap `_e2e-kind.yaml` starts in the background has finished, over files in the output
 * directory instead of a cluster.
 *
 * Shown failing: the wait loop removed ("an absent exit code waits until it appears"), the exit
 * code read untrimmed ("exit code 0 proceeds"), every exit code accepted and the log left out of
 * the error ("a non-zero exit code throws with the log"), and the prerequisite check moved ahead of
 * the wait ("no cluster call before the bootstrap ends").
 */
import fs from "node:fs";
import { test, expect } from "@playwright/test";
import type { Kubectl } from "../../env/k8s.js";
import { awaitBootstrap, readyCluster } from "../../env/provision/k8s.js";

const PENDING = Symbol("pending");

/** What `promise` settles to within `ms`, or `PENDING`. */
async function within<T>(promise: Promise<T>, ms: number): Promise<T | typeof PENDING> {
  return Promise.race([promise, new Promise<typeof PENDING>((resolve) => setTimeout(() => resolve(PENDING), ms))]);
}

/** A bootstrap log in this case's output directory, with its exit code when `code` is given. */
function bootstrapLog(output: string, code?: string): string {
  const log = test.info().outputPath("bootstrap.log");
  fs.writeFileSync(log, output);
  if (code !== undefined) fs.writeFileSync(`${log}.exit`, code);
  return log;
}

test.describe("cluster bootstrap wait", () => {
  test("exit code 0 proceeds", async () => {
    await expect(awaitBootstrap(bootstrapLog("Cluster: creating kind-qip\n", "0\n"), 10)).resolves.toBeUndefined();
  });

  test("a non-zero exit code throws with the log", async () => {
    const log = bootstrapLog("Cluster: creating kind-qip\nERROR: failed to create cluster\n", "1\n");
    await expect(awaitBootstrap(log, 10)).rejects.toThrow(
      "The cluster bootstrap e2e/k8s/bootstrap-cluster.sh exited with 1. Its log:\n" +
        "Cluster: creating kind-qip\nERROR: failed to create cluster\n",
    );
  });

  test("an absent exit code waits until it appears", async () => {
    const log = bootstrapLog("Cluster: creating kind-qip\n");
    const waiting = awaitBootstrap(log, 10);
    expect(await within(waiting, 200)).toBe(PENDING);
    fs.writeFileSync(`${log}.exit`, "0\n");
    expect(await within(waiting, 2_000)).toBeUndefined();
  });

  test("no cluster call before the bootstrap ends", async () => {
    const calls: string[] = [];
    const failing: Kubectl = async (args) => {
      calls.push(args.join(" "));
      throw new Error("no cluster");
    };
    const log = bootstrapLog("Cluster: creating kind-qip\n");
    const ready = readyCluster(log, failing);
    expect(await within(ready, 300)).toBe(PENDING);
    expect(calls).toEqual([]);
    fs.writeFileSync(`${log}.exit`, "0\n");
    // The stub fails every call, so the first prerequisite fails once the wait is over.
    await expect(ready).rejects.toThrow("The k8s target needs a Kubernetes cluster: kubectl has no current context");
    expect(calls[0]).toBe("config current-context");
  });
});
