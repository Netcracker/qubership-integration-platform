/**
 * The run token's alphabet, pinned without a stack.
 *
 * The constraint comes from one line of the product: `SecretControllerV2.createSecret` validates
 * the secret name against `^[a-z]+[-a-z0-9]*$`, and the run token names one. A generator emitting
 * `[A-Za-z0-9]{6}` produces a suite that fails on roughly a third of its runs and passes on the
 * rest, which is worse than one that fails every time — and the failure lands in whatever spec
 * happens to touch a secret, nowhere near the generator.
 *
 * Pinned here rather than by posting a run-token-named secret per run. `SecretControllerV2` exposes
 * create and template and nothing else, and `SecretService` declares no delete, so a spec that
 * proves the regex by using it leaks one unremovable object every run, forever. The live round trip
 * is covered once, by a committed name, in `specs/api/secret-name.spec.ts`.
 */
import { test, expect } from "@playwright/test";
import {
  EXAMPLE_RUN_TOKEN,
  RUN_TOKEN_PATTERN,
  SECRET_FIXTURE_NAME,
  SECRET_NAME_PATTERN,
  callToken,
  carriesRunToken,
  ensureRunToken,
  generateRunToken,
  tokenized,
  workerFolderName,
} from "../../support/run.js";

test.describe("run token", { tag: ["@harness"] }, () => {
  test("a generated token is six characters and names legal secrets", () => {
    for (let i = 0; i < 500; i++) {
      const token = generateRunToken();
      expect(token).toMatch(RUN_TOKEN_PATTERN);
      expect(token).toHaveLength(6);
      expect(token).toMatch(SECRET_NAME_PATTERN);
      // The entity names built from it have to survive the same validator, not just the token.
      expect(workerFolderName(token, 3)).toMatch(SECRET_NAME_PATTERN);
      expect(tokenized(token, "svc")).toMatch(SECRET_NAME_PATTERN);
    }
  });

  test("the secret pattern rejects the names the catalog rejects", () => {
    // Measured against `POST /v2/secret/{name}`: each of these answers 400 with the regex quoted.
    for (const name of ["9bad", "Bad", "e2e-Ab12cd-w0", "e2e_ab12cd", ""]) {
      expect(SECRET_NAME_PATTERN.test(name)).toBe(false);
    }
    expect(SECRET_NAME_PATTERN.test(SECRET_FIXTURE_NAME)).toBe(true);
  });

  test("a token taken from E2E_RUN is validated", () => {
    const original = process.env.E2E_RUN;
    try {
      process.env.E2E_RUN = "Nightly";
      expect(() => ensureRunToken()).toThrow(/E2E_RUN/);
      process.env.E2E_RUN = "ab12cd";
      expect(ensureRunToken()).toBe("ab12cd");
      delete process.env.E2E_RUN;
      expect(ensureRunToken()).toMatch(RUN_TOKEN_PATTERN);
    } finally {
      if (original === undefined) delete process.env.E2E_RUN;
      else process.env.E2E_RUN = original;
    }
  });

  test("carriesRunToken matches only this run's names", () => {
    expect(carriesRunToken("e2e-ab12cd-w0", "ab12cd")).toBe(true);
    expect(carriesRunToken("e2e-ab12cd-svc EXTERNAL", "ab12cd")).toBe(true);
    expect(carriesRunToken("e2e-zz99zz-w0", "ab12cd")).toBe(false);
    expect(carriesRunToken("New external service", "ab12cd")).toBe(false);
    expect(carriesRunToken(undefined, "ab12cd")).toBe(false);
  });

  test("a callToken name carries no run token", () => {
    // The two mints are not interchangeable, and an endpoint mock is where the difference bites. A
    // mock is keyed on `(chainId, elementId)`, both frozen by the fixture, so its **name** is the
    // only thing that says which run made it — and `mocksOfRun` filters on exactly this predicate. A
    // mock named from `callToken` reads as `e2e-mock-<random>`, which carries no run token at all, so
    // no sweep sees it and it goes on answering for that sender on every later run.
    expect(carriesRunToken(tokenized(EXAMPLE_RUN_TOKEN, "mock"), EXAMPLE_RUN_TOKEN)).toBe(true);
    expect(carriesRunToken(`e2e-${callToken("mock")}`, EXAMPLE_RUN_TOKEN)).toBe(false);
  });

  test("a call token is unique and carries no run token", () => {
    // `GET /v1/sessions/external-id/{id}` answers with a single session, so a lookup keyed on
    // anything two calls share returns some other spec's session and the assertion passes for the
    // wrong reason. The run token is what every call would otherwise have in common, so it is the
    // one string a call token must not contain — asserted against the token this run actually minted
    // rather than against the shape of one.
    const run = ensureRunToken();
    const tokens = new Set(Array.from({ length: 200 }, () => callToken()));
    expect(tokens.size).toBe(200);
    for (const token of tokens) {
      expect(token).toMatch(/^call-[a-z][a-z0-9]{5}[a-z][a-z0-9]{5}$/);
      expect(token, "a call token carries the run token, so two calls share a lookup key").not.toContain(run);
    }
  });
});
