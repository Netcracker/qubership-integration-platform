/**
 * Two writing rules that keep a spec on both targets, read off the spec files themselves.
 *
 * Rule 4: only `specs/k8s/` imports `support/kube.ts`, because every other spec runs on Compose
 * too. Rule 20: a runtime spec reads `engineKind` only in a micro pin, and
 * `engine-identity.spec.ts` only, whose subject it is. A branch on the engine kind anywhere else
 * would loosen an assertion for one engine.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { test, expect } from "@playwright/test";

const SPECS = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");

/** Every `.ts` file under `dir`, relative to `specs/`. */
function sources(dir: string): string[] {
  return fs
    .readdirSync(path.join(SPECS, dir), { recursive: true, withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".ts"))
    .map((entry) => path.relative(SPECS, path.join(entry.parentPath, entry.name)));
}

function read(file: string): string {
  return fs.readFileSync(path.join(SPECS, file), "utf-8");
}

/** A micro pin, the one form rule 20 allows. */
const MICRO_PIN = /test\.fail\(engineKind === "micro", [A-Z_]+\.title\)/g;
/** The parameter list a case takes its fixtures in. */
const FIXTURE_PARAMETERS = /async \(\{[^}]*\}\) =>/g;

/** Every `engineKind` a runtime file reads outside a micro pin. */
function engineKindReads(source: string): number {
  return (source.replace(MICRO_PIN, "").replace(FIXTURE_PARAMETERS, "").match(/\bengineKind\b/g) ?? [])
    .length;
}

test("only specs/k8s/ imports support/kube.ts", { tag: ["@infra", "@tier1"] }, () => {
  const all = fs
    .readdirSync(SPECS, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name !== "k8s")
    .flatMap((entry) => sources(entry.name));
  expect(all.length).toBeGreaterThan(0);
  expect(all.filter((file) => /from\s+["'][./]*support\/kube(\.js)?["']/.test(read(file)))).toEqual([]);
  // The check finds an import where one exists.
  expect(sources("k8s").some((file) => /support\/kube\.js/.test(read(file)))).toBe(true);
});

test("a runtime spec reads engineKind only in a micro pin, apart from engine-identity.spec.ts", { tag: ["@infra", "@tier1"] }, () => {
  const files = sources("runtime").filter((file) => path.basename(file) !== "engine-identity.spec.ts");
  expect(files.length).toBeGreaterThan(0);
  expect(files.filter((file) => engineKindReads(read(file)) > 0)).toEqual([]);

  // The reading counts what the rule forbids and leaves out what it allows.
  expect(engineKindReads('async ({ env, engineKind }) => { test.fail(engineKind === "micro", MICRO_XSLT.title);')).toBe(0);
  expect(engineKindReads('async ({ engineKind }) => { if (engineKind === "micro") return;')).toBe(1);
  expect(engineKindReads(read("runtime/engine-identity.spec.ts"))).toBeGreaterThan(0);
});
