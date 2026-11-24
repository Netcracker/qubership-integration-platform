import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { Reporter, TestCase, TestResult, FullResult, FullConfig, Suite, TestError } from "@playwright/test/reporter";
import { runFilters } from "../registry/elements.js";

const authorizationMessage = /^(?:(?:Aggregate)?Error: )?((?:API authorization failed for (?:runtime-catalog|engine|sessions-management|testing-service) during (?:test|setup|cleanup)(?: \(HTTP (?:401|403)\))?|UI authorization failed during (?:login|test \(HTTP (?:401|403)\)))\.)/;
const cleanupMessage = /^(?:(?:Aggregate)?Error: )?(Cleanup failed for [a-z-]+ [0-9a-z-]+\.|Endpoint mock cleanup failed for (?:[0-9a-f-]{36}(?: \(HTTP [1-5]\d\d\))?, )*[0-9a-f-]{36}(?: \(HTTP [1-5]\d\d\))?\.)/i;

const brokerMessage = /^(?:Error: )?(Cannot connect to the external (?:kafka|rabbitmq|pubsub|sftp) broker from the runner\. Check its host address and credentials\.)/;

function summarize(errors: readonly TestError[]) {
  const authorization = new Set<string>();
  const cleanup = new Set<string>();
  const brokers = new Set<string>();
  for (const error of errors) {
    for (let current: TestError | undefined = error; current; current = current.cause) {
      const message = current.message ?? "";
      const denied = authorizationMessage.exec(message)?.[1];
      if (denied) authorization.add(denied);
      const broker = brokerMessage.exec(message)?.[1];
      if (broker) brokers.add(broker);
      const owned = cleanupMessage.exec(message)?.[1];
      if (owned) {
        const http = /^(?:Error: )?(?:GET|POST|PATCH|DELETE|PUT) https?:\/\/\S+ answered ([1-5]\d\d)[:.]/.exec(current.cause?.message ?? "");
        cleanup.add(http ? `${owned} HTTP ${http[1]}.` : owned);
      }
    }
  }
  return {
    authorization: [...authorization].join(" ") || undefined,
    cleanup: [...cleanup].join(" ") || undefined,
    broker: [...brokers].join(" ") || undefined,
  };
}

const coverageTypes = new Set(["covers", "reached", "struck", "vscode-message"]);

function coverageReport(config: FullConfig, suite: Suite, suiteDir: string) {
  const suites = new Map<string, { file: string; specs: object[] }>();
  for (const test of suite.allTests()) {
    const file = path.relative(suiteDir, test.location.file).split(path.sep).join("/");
    let entry = suites.get(file);
    if (!entry) {
      entry = { file, specs: [] };
      suites.set(file, entry);
    }
    const project = test.parent.project()!;
    entry.specs.push({
      id: test.id,
      file,
      line: test.location.line,
      title: test.titlePath().slice(3).join(" › "),
      tags: test.tags.map((tag) => tag.replace(/^@/, "")),
      tests: [{
        projectName: project.name,
        status: test.outcome(),
        expectedStatus: test.expectedStatus,
        annotations: test.annotations.filter(({ type }) => coverageTypes.has(type)),
        results: test.results.map(({ status, duration }) => ({ status, duration })),
      }],
    });
  }
  return {
    config: {
      metadata: { target: config.metadata.target },
      argv: runFilters({ config: { argv: config.argv } }).length ? ["test", "--grep", "<filtered>"] : [],
      projects: config.projects.map(({ name, metadata }) => ({ name, metadata: { group: metadata.group } })),
    },
    suites: [...suites.values()],
  };
}

export default class ProtectedReporter implements Reporter {
  private readonly outputFile: string;
  private readonly suiteDir: string | undefined;
  private started?: { config: FullConfig; suite: Suite };
  private readonly listOnly = process.argv.includes("--list");
  private readonly cases: Array<{ title: string[]; status: string; authorization?: string; cleanup?: string; broker?: string }> = [];

  constructor(options: { outputFile: string; suiteDir?: string }) {
    this.outputFile = options.outputFile;
    this.suiteDir = options.suiteDir;
  }

  onBegin(config: FullConfig, suite: Suite): void {
    this.started = { config, suite };
    if (!this.listOnly) return;
    for (const test of suite.allTests()) console.log(test.titlePath().filter(Boolean).join(" › "));
  }

  onError(error: TestError): void {
    const { authorization, cleanup, broker } = summarize([error]);
    const requirement = /^(?:Error: )?(External project "[a-z0-9-]+" requires (?:sourceRoot|dedicatedStand: true|a stand adapter with a target\(\) implementation|adapter\.brokers\.restart|services\["[a-z-]+"\]|proxy or ui|a healthy [a-z-]+ service \((?:HTTP \d{3}|expected status UP)\))\.)/i
      .exec(error.message ?? "")?.[1];
    console.error([authorization, cleanup, requirement, broker].filter(Boolean).join(" ") || "External suite runner error.");
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    const { authorization, cleanup, broker } = summarize(result.errors);
    const entry = { title: test.titlePath().filter(Boolean), status: result.status, authorization, cleanup, broker };
    this.cases.push(entry);
    if (result.status !== test.expectedStatus) {
      console.error(`${entry.title.join(" › ")}: ${result.status}.${authorization ? ` ${authorization}` : ""}${cleanup ? ` ${cleanup} See the owned resource records.` : ""}${broker ? ` ${broker}` : ""}`);
    }
  }

  onEnd(result: FullResult): void {
    if (this.listOnly) return;
    mkdirSync(path.dirname(this.outputFile), { recursive: true });
    const coverage = this.suiteDir && this.started
      ? coverageReport(this.started.config, this.started.suite, this.suiteDir)
      : undefined;
    writeFileSync(this.outputFile, JSON.stringify({ status: result.status, cases: this.cases, ...coverage }, null, 2) + "\n");
    console.log(`External suite ${result.status}. Report: ${this.outputFile}`);
  }

  printsToStdio(): boolean {
    return true;
  }
}
