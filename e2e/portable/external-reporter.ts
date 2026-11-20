import { mkdirSync, writeFileSync } from "node:fs";
import path from "node:path";
import type { Reporter, TestCase, TestResult, FullResult, FullConfig, Suite, TestError } from "@playwright/test/reporter";

const authorizationMessage = /^(?:(?:Aggregate)?Error: )?((?:API authorization failed for (?:runtime-catalog|engine|sessions-management|testing-service) during (?:test|setup|cleanup)(?: \(HTTP (?:401|403)\))?|UI authorization failed during (?:login|test \(HTTP (?:401|403)\)))\.)/;
const cleanupMessage = /^(?:(?:Aggregate)?Error: )?(Cleanup failed for [a-z-]+ [0-9a-z-]+\.|Endpoint mock cleanup failed for (?:[0-9a-f-]{36}(?: \(HTTP [1-5]\d\d\))?, )*[0-9a-f-]{36}(?: \(HTTP [1-5]\d\d\))?\.)/i;

function summarize(errors: readonly TestError[]) {
  const authorization = new Set<string>();
  const cleanup = new Set<string>();
  for (const error of errors) {
    for (let current: TestError | undefined = error; current; current = current.cause) {
      const message = current.message ?? "";
      const denied = authorizationMessage.exec(message)?.[1];
      if (denied) authorization.add(denied);
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
  };
}

export default class ProtectedReporter implements Reporter {
  private readonly outputFile: string;
  private readonly listOnly = process.argv.includes("--list");
  private readonly cases: Array<{ title: string[]; status: string; authorization?: string; cleanup?: string }> = [];

  constructor(options: { outputFile: string }) {
    this.outputFile = options.outputFile;
  }

  onBegin(_config: FullConfig, suite: Suite): void {
    if (!this.listOnly) return;
    for (const test of suite.allTests()) console.log(test.titlePath().filter(Boolean).join(" › "));
  }

  onError(error: TestError): void {
    const { authorization, cleanup } = summarize([error]);
    console.error([authorization, cleanup].filter(Boolean).join(" ") || "External suite runner error.");
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    const { authorization, cleanup } = summarize(result.errors);
    const entry = { title: test.titlePath().filter(Boolean), status: result.status, authorization, cleanup };
    this.cases.push(entry);
    if (result.status !== test.expectedStatus) {
      console.error(`${entry.title.join(" › ")}: ${result.status}.${authorization ? ` ${authorization}` : ""}${cleanup ? ` ${cleanup} See the owned resource records.` : ""}`);
    }
  }

  onEnd(result: FullResult): void {
    if (this.listOnly) return;
    mkdirSync(path.dirname(this.outputFile), { recursive: true });
    writeFileSync(this.outputFile, JSON.stringify({ status: result.status, cases: this.cases }, null, 2) + "\n");
    console.log(`External suite ${result.status}. Report: ${this.outputFile}`);
  }

  printsToStdio(): boolean {
    return true;
  }
}
