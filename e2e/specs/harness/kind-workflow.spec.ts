/**
 * The reusable workflow `_e2e-kind.yaml` runs the suite on kind for the nightly `e2e-k8s.yaml` and
 * for the lane jobs of `e2e-pr.yaml`. These cases read it against its callers and against the
 * nightly's run history.
 *
 * Shown failing: `secrets: inherit` on the nightly, `pull-request: true` dropped from the `classic`
 * job, `build: all` passed by it, `testing-service` dropped from the `ui` job's build, and the
 * `micro` job's build without micro-engine ("callers pass declared inputs and never inherit
 * secrets");
 * the outcomes uploaded as `e2e-outcomes-nightly` and the cluster dump without the suffix ("the
 * history fetch downloads the outcomes the nightly uploads"); the email step without
 * `!inputs.pull-request` and the partial reconcile under `always()` ("a pull request run skips the
 * nightly's history and email").
 */
import fs from "node:fs";
import path from "node:path";
import yaml from "js-yaml";
import { test, expect, type FullConfig } from "@playwright/test";
import { ROLES } from "../../env/containers.js";
import { repoRoot } from "../../env/host.js";
import { microProjects } from "../../env/provision.js";
import { imagesToBuild } from "../../env/provision/k8s.js";
import { targetSetup } from "../../env/target-setup.js";
import { suiteProjects } from "../../playwright.config.js";
import { laneFlags } from "../../support/lanes.mjs";
import { withEnv } from "../../support/with-env.js";

const WORKFLOWS = path.join(repoRoot(), ".github", "workflows");
const KIND = "_e2e-kind.yaml";
const NIGHTLY = "e2e-k8s.yaml";
const CALLERS = [NIGHTLY, "e2e-pr.yaml"];
const SUFFIX = "${{ env.ARTIFACT_SUFFIX }}";

interface Step {
  name?: string;
  if?: string;
  uses?: string;
  run?: string;
  env?: Record<string, string>;
  with?: Record<string, unknown>;
}
interface Job {
  uses?: string;
  with?: Record<string, unknown>;
  secrets?: Record<string, string> | string;
  permissions?: Record<string, string>;
  env?: Record<string, string>;
  steps?: Step[];
}
interface Workflow {
  on: {
    workflow_call?: { inputs: Record<string, { required?: boolean; default?: unknown }>; secrets: Record<string, unknown> };
  };
  jobs: Record<string, Job>;
}

const read = (file: string) => yaml.load(fs.readFileSync(path.join(WORKFLOWS, file), "utf-8")) as Workflow;
const kind = () => read(KIND);
const kindJob = () => Object.values(kind().jobs)[0];
const callers = (file: string) => Object.entries(read(file).jobs).filter(([, job]) => job.uses === `./.github/workflows/${KIND}`);

/** The projects a caller's lane runs on kind that run the micro engine, as the provisioner reads them. */
function laneMicroProjects(job: Job): string[] {
  const brokers = job.with?.brokers === false ? "0" : undefined;
  const projects = withEnv({ E2E_BROKERS: brokers }, () => suiteProjects(targetSetup("k8s"))).map((each) => ({
    ...each,
    dependencies: each.dependencies ?? [],
    use: each.use ?? {},
  }));
  const argv = ["node", "playwright", "test", ...laneFlags(String(job.with?.lane ?? ""))];
  return microProjects({ argv, projects } as unknown as FullConfig);
}

function step(name: string): Step {
  const found = kindJob().steps?.find((each) => each.name === name);
  if (!found) throw new Error(`${KIND} has no step "${name}"`);
  return found;
}

test.describe("kind workflow", () => {
  test("callers pass declared inputs and never inherit secrets", () => {
    const call = kind().on.workflow_call!;
    expect(callers(NIGHTLY).map(([name]) => name)).toEqual(["e2e"]);
    for (const file of CALLERS) {
      for (const [name, job] of callers(file)) {
        const where = `${file} ${name}`;
        const passed = Object.keys(job.with ?? {});
        expect(passed.filter((input) => !(input in call.inputs)), where).toEqual([]);
        const required = Object.entries(call.inputs).filter(([, input]) => input.required).map(([input]) => input);
        expect(required.filter((input) => !passed.includes(input)), where).toEqual([]);
        // A called workflow gets no secret it is not handed, and `inherit` would hand it every one.
        expect(job.secrets, where).not.toBe("inherit");
        expect(Object.keys(job.secrets ?? {}).filter((secret) => !(secret in call.secrets)), where).toEqual([]);
        expect(job.permissions, where).toEqual({ actions: "read", contents: "read", packages: "read" });
      }
    }
    for (const [name, job] of callers("e2e-pr.yaml")) {
      expect(job.with?.["pull-request"], name).toBe(true);
      // The GHCR images lag main until a release, so a pull request builds every image its lane runs,
      // and micro-engine only for a lane that selects a micro project.
      const build = imagesToBuild(String(job.with?.build ?? call.inputs.build.default));
      const micro = laneMicroProjects(job).length ? ["micro-engine"] : [];
      expect([...build].sort(), name).toEqual([...ROLES, ...micro].sort());
    }
    expect(callers(NIGHTLY)[0][1].secrets, "the email step needs the password").toEqual({ EMAIL_PASSWORD: "${{ secrets.EMAIL_PASSWORD }}" });
  });

  test("the nightly runs the whole suite and mails a scheduled run", () => {
    const [[, nightly]] = callers(NIGHTLY);
    expect(nightly.with).toEqual({
      name: "Full suite on kind",
      workers: "${{ inputs.workers }}",
      email: "${{ github.event_name == 'schedule' || inputs.email }}",
      "timeout-minutes": 180,
      "global-timeout": 140,
    });
  });

  test("the history fetch downloads the outcomes the nightly uploads", () => {
    const fetch = step("Fetch the run history").run ?? "";
    expect(fetch).toContain(`--workflow ${NIGHTLY} `);
    const downloaded = /gh run download .* -n (\S+)/.exec(fetch)?.[1];
    const uploaded = String(step("Upload the outcomes").with?.name);
    // The suffix is empty on the nightly.
    expect(uploaded.replace(SUFFIX, "")).toBe(downloaded);
    expect(kindJob().env?.ARTIFACT_SUFFIX).toBe("${{ inputs.pull-request && format('-{0}', inputs.name) || '' }}");
    // Pull request jobs share their caller's run, where two artifacts cannot take one name.
    const uploads = kindJob().steps?.filter((each) => each.uses?.startsWith("actions/upload-artifact@")) ?? [];
    expect(uploads.length).toBeGreaterThan(0);
    for (const upload of uploads) {
      const named = String(upload.with?.name ?? upload.with?.path);
      expect(named, upload.name).toContain(SUFFIX);
    }
  });

  test("a pull request run skips the nightly's history and email", () => {
    for (const name of ["Fetch the run history", "Reconcile the coverage registry", "Write the run email"]) {
      expect(step(name).if, name).toContain("!inputs.pull-request");
    }
    const partial = step("Reconcile the lane's coverage");
    expect(partial.run).toBe("npm run reconcile -- --partial");
    // No status function, so the step runs only after every earlier step passed.
    expect(partial.if).toBe("${{ inputs.pull-request }}");
    const suite = step("Run the suite");
    expect(suite.env?.E2E_KEEP).toBe("${{ inputs.pull-request && '1' || '' }}");
    expect(suite.run).toContain(`if [ "$PULL_REQUEST" = true ]; then args+=(--max-failures=1); fi`);
    expect(step("Name the last nightly run").if).toBe("${{ always() && inputs.pull-request }}");
  });
});
