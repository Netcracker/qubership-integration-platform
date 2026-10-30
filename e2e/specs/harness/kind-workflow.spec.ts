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
 * nightly's history and email"); a dispatch falling back to `vars.EMAIL_TO` and a boolean
 * `email-to` ("a schedule mails vars.EMAIL_TO, a dispatch its email-to"); the email step without
 * the recipients gate and the action reading `EMAIL_TO` from `env` ("only a run with recipients is
 * mailed, and only to them"); the recipients read after the suite, and `secrets.EMAIL_TO` in the
 * suite's `env` ("the recipients are masked before any step can print them"); an address check that accepts `a@example`, `a b@example.com`, or `a@@example.com` ("the
 * address check refuses a malformed entry"); no mask commands, the entry in the error, and the
 * output written before the check ("the script masks every entry before it checks them"); the
 * `background-bootstrap` default true, and the `ui` job without it ("the bootstrap runs in the
 * background only for the PR lanes"); the foreground bootstrap step without its `if`, the start step
 * after the root dependencies, and the suite's `E2E_BOOTSTRAP_LOG` without the input ("with the
 * input, the suite waits for the bootstrap it started"); the subshell without `&`, and the exit
 * code written under another name ("the start step returns before the bootstrap ends").
 */
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import yaml from "js-yaml";
import { test, expect, type FullConfig } from "@playwright/test";
import { ROLES } from "../../env/containers.js";
import { repoRoot } from "../../env/host.js";
import { microProjects } from "../../env/provision.js";
import { awaitBootstrap, imagesToBuild } from "../../env/provision/k8s.js";
import { targetSetup } from "../../env/target-setup.js";
import { suiteProjects } from "../../playwright.config.js";
import { recipients } from "../../support/email-recipients.mjs";
import { laneFlags } from "../../support/lanes.mjs";
import { withEnv } from "../../support/with-env.js";

const WORKFLOWS = path.join(repoRoot(), ".github", "workflows");
const KIND = "_e2e-kind.yaml";
const NIGHTLY = "e2e-k8s.yaml";
const CALLERS = [NIGHTLY, "e2e-pr.yaml"];
const SUFFIX = "${{ env.ARTIFACT_SUFFIX }}";
const SCRIPT = path.join(repoRoot(), "e2e", "support", "email-recipients.mjs");

interface Step {
  id?: string;
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
    workflow_dispatch?: { inputs: Record<string, { type?: string; default?: unknown }> };
    workflow_call?: { inputs: Record<string, { required?: boolean; type?: string; default?: unknown }>; secrets: Record<string, unknown> };
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
    expect(Object.keys(callers(NIGHTLY)[0][1].secrets ?? {}), "the email steps need the password and the recipients").toEqual(["EMAIL_PASSWORD", "EMAIL_TO"]);
  });

  test("the nightly runs the whole suite", () => {
    const [[, nightly]] = callers(NIGHTLY);
    expect(nightly.with).toEqual({
      name: "Full suite on kind",
      workers: "${{ inputs.workers }}",
      "timeout-minutes": 180,
      "global-timeout": 140,
    });
  });

  // The recipients reach the called workflow as a secret: an input would show in the header of the
  // step that masks it.
  test("a schedule mails vars.EMAIL_TO, a dispatch its email-to", () => {
    const [[, nightly]] = callers(NIGHTLY);
    const secrets = nightly.secrets as Record<string, string>;
    expect(secrets.EMAIL_PASSWORD).toBe("${{ secrets.EMAIL_PASSWORD }}");
    expect(secrets.EMAIL_TO).toBe("${{ github.event_name == 'schedule' && vars.EMAIL_TO || inputs.email-to }}");
    expect(read(NIGHTLY).on.workflow_dispatch?.inputs["email-to"]).toMatchObject({ type: "string", default: "" });
    expect(Object.keys(read(NIGHTLY).on.workflow_dispatch?.inputs ?? {})).not.toContain("email");
    // A dispatch with an empty email-to then sends nothing, since the called workflow never reads vars.EMAIL_TO.
    expect(fs.readFileSync(path.join(WORKFLOWS, KIND), "utf-8")).not.toContain("vars.EMAIL_TO");
  });

  test("only a run with recipients is mailed, and only to them", () => {
    // "a pull request run skips the nightly's history and email" checks the !inputs.pull-request half.
    expect(step("Write the run email").if).toContain("steps.recipients.outputs.to != ''");
    const send = step("Email the run");
    expect(send.if).toBe("${{ always() && steps.email.outcome == 'success' }}");
    expect(send.with?.to).toBe("${{ steps.recipients.outputs.to }}");
    // The action falls back to EMAIL_TO from env for an empty `to`.
    expect(Object.keys(send.env ?? {})).not.toContain("EMAIL_TO");
  });

  test("the recipients are masked before any step can print them", () => {
    const steps = kindJob().steps ?? [];
    const masking = steps.findIndex((each) => each.id === "recipients");
    expect(steps[masking]).toMatchObject({
      name: "Read the email recipients",
      env: { EMAIL_TO: "${{ secrets.EMAIL_TO }}" },
      run: "node e2e/support/email-recipients.mjs",
    });
    expect(masking).toBe(steps.findIndex((each) => each.name === "Checkout") + 1);
    const mentions = (each: Step) => JSON.stringify(each).includes("secrets.EMAIL_TO");
    expect(steps.filter(mentions).map((each) => each.name)).toEqual(["Read the email recipients"]);
    for (const name of ["Run the suite", "Write the run email", "Email the run"]) {
      expect(masking, name).toBeLessThan(steps.findIndex((each) => each.name === name));
    }
    expect(JSON.stringify(kindJob().env ?? {})).not.toContain("EMAIL_TO");
  });

  test("the address check refuses a malformed entry", () => {
    expect(recipients("a@example.com, b@example.com")).toEqual(["a@example.com", "b@example.com"]);
    expect(recipients(" a@example.com ,, ")).toEqual(["a@example.com"]);
    expect(recipients("")).toEqual([]);
    expect(recipients(undefined)).toEqual([]);
    for (const [list, position] of [
      ["a@example", "1 of 1"],
      ["a@example.com, a b@example.com", "2 of 2"],
      ["a@@example.com", "1 of 1"],
    ]) {
      expect(() => recipients(list), list).toThrow(`Recipient ${position} is not an email address`);
    }
  });

  test("the script masks every entry before it checks them", () => {
    const run = (list: string) => {
      const output = test.info().outputPath(`output-${list.length}`);
      fs.writeFileSync(output, "");
      const result = spawnSync(process.execPath, [SCRIPT], { env: { ...process.env, EMAIL_TO: list, GITHUB_OUTPUT: output }, encoding: "utf-8" });
      return { status: result.status, stdout: result.stdout, output: fs.readFileSync(output, "utf-8") };
    };
    const good = run("a@example.com, b@example.com");
    expect(good.status).toBe(0);
    expect(good.output).toBe("to=a@example.com,b@example.com\n");
    expect(good.stdout).toBe("::add-mask::a@example.com\n::add-mask::b@example.com\nThe run email goes to 2 addresses.\n");

    const bad = run("a@example.com, example.com");
    expect(bad.status).toBe(1);
    expect(bad.output).toBe("");
    const lines = bad.stdout.split("\n");
    expect(lines.slice(0, 2)).toEqual(["::add-mask::a@example.com", "::add-mask::example.com"]);
    // Only the mask commands name an address, and the runner prints none of them.
    expect(lines.slice(2).join("\n")).not.toContain("example.com");
    expect(lines[2]).toMatch(/^::error::Recipient 2 of 2 is not an email address/);

    expect(run("")).toEqual({ status: 0, stdout: "The run email is not sent.\n", output: "to=\n" });
  });

  test("the bootstrap runs in the background only for the PR lanes", () => {
    expect(kind().on.workflow_call?.inputs["background-bootstrap"]).toMatchObject({ type: "boolean", default: false });
    // "the nightly runs the whole suite" pins the nightly's inputs, which leave it out.
    const lanes = callers("e2e-pr.yaml");
    expect(lanes.map(([name]) => name).sort()).toEqual(["classic", "micro", "ui"]);
    for (const [name, job] of lanes) expect(job.with?.["background-bootstrap"], name).toBe(true);
  });

  test("with the input, the suite waits for the bootstrap it started", () => {
    const steps = kindJob().steps ?? [];
    const at = (name: string) => steps.findIndex((each) => each.name === name);
    expect(step("Bootstrap the cluster").if).toBe("${{ !inputs.background-bootstrap }}");
    expect(step("Start the cluster bootstrap").if).toBe("${{ inputs.background-bootstrap }}");
    // Started once the tools are on PATH, so it runs beside npm ci, Chromium, and the image builds.
    expect(at("Start the cluster bootstrap")).toBe(at("Bootstrap the cluster") + 1);
    expect(at("Bootstrap the cluster")).toBeGreaterThan(at("Install kind and istioctl"));
    expect(at("Start the cluster bootstrap")).toBeLessThan(at("Install root dependencies"));
    const between = steps.slice(at("Start the cluster bootstrap") + 1, at("Run the suite"));
    expect(between.filter((each) => /\b(kubectl|kind|helm|istioctl)\b/.test(each.run ?? "")).map((each) => each.name)).toEqual([]);
    // The provisioner waits for the exit code only when the input started the bootstrap; the nightly
    // would otherwise wait for a file nothing writes.
    expect(step("Run the suite").env?.E2E_BOOTSTRAP_LOG).toBe("${{ inputs.background-bootstrap && env.BOOTSTRAP_LOG || '' }}");
    expect(kindJob().env?.BOOTSTRAP_LOG).toBe("${{ github.workspace }}/e2e/.e2e-k8s-bootstrap.log");
    expect(String(step("Upload the report").with?.path)).toContain("e2e/.e2e-k8s-*");
  });

  test("the start step returns before the bootstrap ends", async () => {
    const run = (code: number) => {
      const root = test.info().outputPath(`checkout-${code}`);
      const script = path.join(root, "e2e", "k8s", "bootstrap-cluster.sh");
      fs.mkdirSync(path.dirname(script), { recursive: true });
      fs.writeFileSync(script, `#!/usr/bin/env bash\necho "Cluster: creating kind-qip"\nsleep 1\necho "exit ${code}" >&2\nexit ${code}\n`, { mode: 0o755 });
      const log = path.join(root, "bootstrap.log");
      // GitHub runs a step's script with bash -e.
      const result = spawnSync("bash", ["-e", "-c", step("Start the cluster bootstrap").run ?? ""], {
        cwd: root,
        env: { ...process.env, BOOTSTRAP_LOG: log },
        encoding: "utf-8",
      });
      expect(result.status).toBe(0);
      expect(fs.existsSync(`${log}.exit`), "the exit code before the bootstrap ended").toBe(false);
      const timeout = new Promise<never>((_, reject) => setTimeout(() => reject(new Error("no exit code within 10 s")), 10_000));
      return Promise.race([awaitBootstrap(log, 50), timeout]);
    };
    await expect(run(0)).resolves.toBeUndefined();
    await expect(run(3)).rejects.toThrow("exited with 3. Its log:\nCluster: creating kind-qip\nexit 3\n");
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
