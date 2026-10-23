/** Local test tooling owns its temporary store and needs no product stack or corpus. */
import { test, expect } from "@playwright/test";
import { execFileSync, spawn, type ChildProcess } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { resolve, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createInterface } from "node:readline";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const entry = resolve(root, "ai-service/e2e/run.py");
let directory: string, server: ChildProcess, url: string;

test.beforeEach(async () => {
  directory = mkdtempSync(join(tmpdir(), "ai-test-ui-"));
  mkdirSync(join(directory, "package/testing"), { recursive: true });
  const fixture = join(directory, "fixture.py");
  writeFileSync(
    fixture,
    `import json, os, sys, time
from pathlib import Path
if sys.argv[1] == 'wait':
    print('Waiting for cancellation', flush=True)
    time.sleep(120)
else:
    root = Path(os.environ['QIP_TEST_TASK_DIR'])
    (root / 'result.json').write_text(json.dumps({'schemaVersion': 1, 'status': 'FAIL', 'failures': [
      {'caseId': 'import-v2', 'category': 'service-import', 'error': {'message': '<script>window.injected=true</script> Wrong specification version'},
       'recovery': {'status': 'FAILED', 'attempts': [{'number': 1, 'feedback': 'Readback still returns v1'}],
         'diagnosis': {'status': 'HYPOTHESIS', 'summary': 'The retry may have reused a stale binding.'}}}]}))
`,
  );
  writeFileSync(
    join(directory, "package/testing/suites.json"),
    JSON.stringify({
      contract: "qip-ai-tests/v1",
      version: "fixture",
      profiles: {
        "report-fixture": ["fixture-report"],
        "cancel-fixture": ["fixture-wait"],
      },
      suites: [
        {
          id: "fixture-report",
          usesLlm: false,
          timeoutSeconds: 30,
          format: "result",
          command: ["{python}", fixture, "report"],
          reports: "{task_dir}/result.json",
        },
        {
          id: "fixture-wait",
          usesLlm: false,
          timeoutSeconds: 180,
          command: ["{python}", fixture, "wait"],
        },
      ],
    }),
  );
  server = spawn("python3", [
    entry,
    "--package",
    join(directory, "package"),
    "--store",
    join(directory, "runs"),
    "serve",
    "--port",
    "0",
  ]);
  const lines = createInterface({ input: server.stdout! });
  url = await new Promise<string>((accept, reject) => {
    const timer = setTimeout(
      () => reject(new Error("The local test UI did not start")),
      10_000,
    );
    server.once("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`UI server exited: ${code}`));
    });
    lines.on("line", (line) => {
      if (line.startsWith("Test results: ")) {
        clearTimeout(timer);
        accept(line.slice(14));
      }
    });
  });
});

test.afterEach(async () => {
  if (url && server?.exitCode === null) {
    const runs = await (await fetch(url + "/api/runs")).json();
    for (const run of runs) {
      await fetch(url + `/api/runs/${run.id}/cancel`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: "{}",
      });
    }
    await expect
      .poll(async () => {
        const current = await (await fetch(url + "/api/runs")).json();
        return current.every((run: { status: string }) =>
          ["PASS", "FAIL", "ERROR", "BLOCKED", "CANCELLED"].includes(
            run.status,
          ),
        );
      })
      .toBe(true);
  }
  if (server?.exitCode === null) {
    const exited = new Promise<void>((accept) =>
      server.once("exit", () => accept()),
    );
    server.kill("SIGTERM");
    await exited;
  }
  rmSync(directory, { recursive: true, force: true });
});

test(
  "an agent run appears in the UI with separate failure and recovery details",
  { tag: ["@ui", "@tier1"] },
  async ({ page, request }) => {
    const run = JSON.parse(
      execFileSync(
        "python3",
        [
          entry,
          "--package",
          join(directory, "package"),
          "--store",
          join(directory, "runs"),
          "start",
          "report-fixture",
        ],
        { encoding: "utf8" },
      ),
    );
    await page.goto(url + "/#" + run.id);
    await expect(
      page.getByRole("heading", { name: "F001 · import-v2" }),
    ).toBeVisible();
    await expect(
      page.getByText(
        "<script>window.injected=true</script> Wrong specification version",
        { exact: true },
      ),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "Root cause · UNKNOWN" }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: "Why recovery failed · HYPOTHESIS" }),
    ).toBeVisible();
    expect(await page.evaluate(() => "injected" in window)).toBe(false);
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "F001 · import-v2" }),
    ).toBeVisible();
    const response = await request.get(url + `/api/runs/${run.id}/report.md`);
    expect(await response.text()).toContain("Recovery: FAILED");
    const again = page.waitForResponse(
      (response) =>
        response.url() === url + "/api/runs" &&
        response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Run again" }).click();
    const next = await (await again).json();
    expect(next.parentRunId).toBe(run.id);
    expect(next.id).not.toBe(run.id);
    await expect
      .poll(
        async () =>
          (await (await request.get(url + `/api/runs/${next.id}`)).json())
            .status,
      )
      .toBe("FAIL");
    expect(
      (await (await request.get(url + `/api/runs/${run.id}`)).json()).status,
    ).toBe("FAIL");
  },
);

test(
  "a manual run can be cancelled and remains in history",
  { tag: ["@ui", "@tier1"] },
  async ({ page, request }) => {
    await page.goto(url);
    await page
      .getByRole("combobox", { name: "Profile", exact: true })
      .selectOption("cancel-fixture");
    const started = page.waitForResponse(
      (response) =>
        response.url() === url + "/api/runs" &&
        response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Start run", exact: true }).click();
    const run = await (await started).json();
    await expect
      .poll(
        async () =>
          (await (await request.get(url + `/api/runs/${run.id}`)).json())
            .tasks[0].status,
      )
      .toBe("RUNNING");
    await page.getByRole("button", { name: "Cancel run" }).click();
    await expect
      .poll(
        async () =>
          (await (await request.get(url + `/api/runs/${run.id}`)).json())
            .status,
      )
      .toBe("CANCELLED");
    await page.reload();
    await expect(
      page.getByRole("navigation", { name: "Run history" }),
    ).toContainText(run.id);
    await expect(
      page.getByRole("navigation", { name: "Run history" }),
    ).toContainText("CANCELLED");
  },
);
