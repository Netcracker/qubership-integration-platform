/**
 * Two writing rules that keep a spec on both targets, read off the spec files themselves.
 *
 * Rule 4: only `specs/k8s/` imports `support/kube.ts`, because every other spec runs on Compose
 * too. Rule 20: among the runtime specs only
 * `engine-identity.spec.ts` reads `engineKind`, because its subject is the engine kind. A branch on the engine kind anywhere else
 * would loosen an assertion for one engine. A case the micro engine cannot run carries both the `@classic-only` tag, which
 * the micro projects filter on, and a `classic-only` annotation, which gives the reason.
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

/** The parameter list a case takes its fixtures in. */
const FIXTURE_PARAMETERS = /async \(\{[^}]*\}\) =>/g;

/** Every `engineKind` a runtime file reads outside its fixture parameters. */
function engineKindReads(source: string): number {
  return (source.replace(FIXTURE_PARAMETERS, "").match(/\bengineKind\b/g) ?? []).length;
}

/** The lines that carry the `@classic-only` tag or the `classic-only` annotation without the other, or the annotation without a reason. */
function classicOnlyMismatches(source: string): string[] {
  return source.split("\n").filter((line) => {
    const tagged = line.includes('"@classic-only"');
    const annotated = line.includes('type: "classic-only"');
    return tagged !== annotated || (annotated && !/type: "classic-only", description: "[^"]+"/.test(line));
  });
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

test("only engine-identity.spec.ts reads engineKind among the runtime specs", { tag: ["@infra", "@tier1"] }, () => {
  const files = sources("runtime").filter((file) => path.basename(file) !== "engine-identity.spec.ts");
  expect(files.length).toBeGreaterThan(0);
  expect(files.filter((file) => engineKindReads(read(file)) > 0)).toEqual([]);

  // The reading counts what the rule forbids and leaves out what it allows.
  expect(engineKindReads('async ({ env, engineKind }) => {')).toBe(0);
  expect(engineKindReads('async ({ engineKind }) => { if (engineKind === "micro") return;')).toBe(1);
  expect(engineKindReads(read("runtime/engine-identity.spec.ts"))).toBeGreaterThan(0);
});

test("a classic-only case carries both the tag and the annotation with its reason", { tag: ["@infra", "@tier1"] }, () => {
  // `specs/schema/` is left out: its own cases quote each half without the other.
  const all = fs
    .readdirSync(SPECS, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && entry.name !== "schema")
    .flatMap((entry) => sources(entry.name));
  expect(all.filter((file) => classicOnlyMismatches(read(file)).length > 0)).toEqual([]);
  // The rule has cases to read.
  expect(all.some((file) => read(file).includes('"@classic-only"'))).toBe(true);

  const tag = 'tag: ["@engine", "@tier2", "@classic-only"]';
  const annotation = 'annotation: { type: "classic-only", description: "a reason" }';
  expect(classicOnlyMismatches(`test("a", { ${tag}, ${annotation} }, async () => {`)).toEqual([]);
  expect(classicOnlyMismatches(`test("a", { ${tag} }, async () => {`)).toHaveLength(1);
  expect(classicOnlyMismatches(`test("a", { tag: ["@engine"], ${annotation} }, async () => {`)).toHaveLength(1);
  expect(classicOnlyMismatches(`test("a", { ${tag}, annotation: { type: "classic-only", description: "" } }, async () => {`)).toHaveLength(1);
});
