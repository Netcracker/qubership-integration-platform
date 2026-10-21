/**
 * The axis fixture generator, against known entries. No stack.
 *
 * The refusals are the point: a declaration naming an axis the element lacks, a value the axis
 * lacks, or a hand-written fixture that never sets the value would otherwise put a chain in the
 * corpus that tests nothing.
 */
import { test, expect } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import JSZip from "jszip";
import yaml from "js-yaml";
import { loadElementSchemas } from "../../registry/discriminators.js";
import {
  axisFixtureName,
  axisFixtures,
  generateAxisChain,
  THROWING_STEP,
  writeAxisFixtures,
  type AxisFixture,
} from "../../fixtures/axis-generator.js";
import {
  CHAIN_FIXTURE_DIR,
  assembleCorpus,
  corpusFixtureNames,
  corpusFixtures,
} from "../../fixtures/templating.js";
import { EXAMPLE_RUN_TOKEN } from "../../support/run.js";

const SENDER_PUT: AxisFixture = {
  family: "http-sender",
  axisPath: "httpMethod",
  value: "PUT",
  properties: { uri: "http://localhost/echo" },
};

const TRIGGER_DUPLICATE_IGNORED: AxisFixture = {
  family: "http-trigger",
  axisPath: "idempotency/actionOnDuplicate",
  value: "ignore",
  properties: { idempotency: { enabled: true, keyExpression: "${header.key}", contextExpression: "e2e" } },
};

interface Element {
  id: string;
  name: string;
  type: string;
  properties: Record<string, unknown>;
}

interface Chain {
  id: string;
  name: string;
  content: { elements: Element[]; dependencies: Array<{ from: string; to: string }> };
}

let schemas: Map<string, unknown>;

test.describe("axis generator", { tag: ["@harness"] }, () => {
  test.beforeAll(async () => {
    schemas = await loadElementSchemas();
  });

  function generate(fixture: AxisFixture): Chain {
    return generateAxisChain(fixture, schemas) as unknown as Chain;
  }

  test("a chain is generated from an element, an axis, and a value", () => {
    const chain = generate(SENDER_PUT);
    const [trigger, underTest] = chain.content.elements;

    expect(chain.content.elements).toHaveLength(2);
    expect(trigger.type).toBe("http-trigger");
    expect(underTest).toMatchObject({
      type: "http-sender",
      properties: { uri: "http://localhost/echo", httpMethod: "PUT" },
    });
    expect(chain.content.dependencies).toEqual([{ from: trigger.id, to: underTest.id }]);
  });

  test("the chain, route, and element are named after the axis value", () => {
    const chain = generate(SENDER_PUT);
    const [trigger, underTest] = chain.content.elements;

    expect(axisFixtureName(SENDER_PUT)).toBe("http-sender-httpMethod-PUT");
    expect(chain.name).toBe("e2e-{{RUN}}-axis-http-sender-httpMethod-PUT");
    expect(trigger.properties.contextPath).toBe("e2e-{{RUN}}-axis-http-sender-httpMethod-PUT");
    // The instance name is what a session trace reports as `elementName`.
    expect(underTest.name).toBe("httpMethod=PUT");
  });

  test("an http-trigger axis is set on the trigger, nested paths too", () => {
    const chain = generate(TRIGGER_DUPLICATE_IGNORED);

    expect(chain.content.elements).toHaveLength(1);
    const [trigger] = chain.content.elements;
    expect(trigger.name).toBe("idempotency/actionOnDuplicate=ignore");
    expect(trigger.properties.idempotency).toEqual({
      enabled: true,
      keyExpression: "${header.key}",
      contextExpression: "e2e",
      actionOnDuplicate: "ignore",
    });
    expect(chain.content.dependencies).toEqual([]);
  });

  test("a downstream element is wired after the element under test", () => {
    const chain = generate({
      family: "http-trigger",
      axisPath: "handleChainFailureAction",
      value: "script",
      properties: { chainFailureHandlerContainer: { script: "exchange.getMessage().setBody('handled')" } },
      downstream: THROWING_STEP,
    });
    const [trigger, downstream] = chain.content.elements;

    expect(chain.content.elements).toHaveLength(2);
    expect(trigger.properties.handleChainFailureAction).toBe("script");
    expect(downstream).toMatchObject({ ...THROWING_STEP });
    expect(chain.content.dependencies).toEqual([{ from: trigger.id, to: downstream.id }]);
  });

  test("ids are stable across generations, distinct across values", () => {
    const once = generate(SENDER_PUT);
    const again = generate(SENDER_PUT);
    const other = generate({ ...SENDER_PUT, value: "POST" });

    expect(again.id).toBe(once.id);
    expect(again.content.elements.map((each) => each.id)).toEqual(once.content.elements.map((each) => each.id));
    expect(other.id).not.toBe(once.id);
    expect(other.content.elements[0].properties.contextPath).not.toBe(once.content.elements[0].properties.contextPath);
  });

  test("an unknown axis fails generation, naming the element's axes", () => {
    // A mutation check kept in the suite:
    // `header-modification` declares no axis.
    expect(() => generate({ family: "header-modification", axisPath: "httpMethod", value: "PUT" })).toThrow(
      /header-modification declares no axis httpMethod; its axes are none/,
    );
    expect(() => generate({ ...SENDER_PUT, axisPath: "accessControlType" })).toThrow(
      /http-sender declares no axis accessControlType; its axes are .*httpMethod/,
    );
  });

  test("a value the axis does not declare fails generation", () => {
    expect(() => generate({ ...SENDER_PUT, value: "TRACE" })).toThrow(/httpMethod has no value "TRACE"/);
  });

  test("an element with no schema fails generation", () => {
    expect(() => generate({ ...SENDER_PUT, family: "no-such-element" })).toThrow(/no element schema for no-such-element/);
  });

  // The chain is validated when it is generated, before any deploy.

  test("a template missing a value's companion fails generation", () => {
    expect(() => generate({ family: "http-trigger", axisPath: "accessControlType", value: "RBAC" })).toThrow(
      /does not validate.*roles/,
    );
  });

  // It generates no chain of its own.

  test("a hand-written declaration is checked against its fixture", async () => {
    const handWritten: AxisFixture = {
      family: "http-trigger",
      axisPath: "handleValidationAction",
      value: "default",
      handWritten: "http-echo",
    };
    const dir = test.info().outputPath("axes");

    expect(await writeAxisFixtures([handWritten], dir)).toEqual([]);

    await expect(writeAxisFixtures([{ ...handWritten, value: "script" }], dir)).rejects.toThrow(
      /http-echo has no http-trigger element with handleValidationAction="script"/,
    );
    await expect(writeAxisFixtures([{ ...handWritten, handWritten: "no-such-fixture" }], dir)).rejects.toThrow(
      /no hand-written fixture directory/,
    );
  });

  test("generated chains land where the corpus assembler reads them", async () => {
    const dir = test.info().outputPath("axes");
    const names = await writeAxisFixtures([SENDER_PUT, TRIGGER_DUPLICATE_IGNORED], dir);
    expect(names).toEqual(["http-sender-httpMethod-PUT", "http-trigger-idempotency-actionOnDuplicate-ignore"]);

    const dirs = [CHAIN_FIXTURE_DIR, dir];
    expect(corpusFixtureNames(dirs)).toEqual(expect.arrayContaining([...names, "http-echo"]));

    const { archive, documents } = await assembleCorpus(names, EXAMPLE_RUN_TOKEN, dirs);
    const sender = documents.find((each) => each.document.name === `e2e-${EXAMPLE_RUN_TOKEN}-axis-${names[0]}`);
    expect(sender, "the generated chain reached the assembled corpus with the run token substituted").toBeTruthy();
    const zip = await JSZip.loadAsync(archive);
    expect(zip.file(`chains/${sender!.id}/${sender!.id}.chain.cip.yaml`)).not.toBeNull();
  });

  test("a rewrite drops the chains the declarations no longer name", async () => {
    const dir = test.info().outputPath("axes");
    await writeAxisFixtures([SENDER_PUT, TRIGGER_DUPLICATE_IGNORED], dir);
    await writeAxisFixtures([SENDER_PUT], dir);

    expect(corpusFixtureNames([dir])).toEqual(["http-sender-httpMethod-PUT"]);
    const written = yaml.load(
      fs.readFileSync(path.join(dir, "http-sender-httpMethod-PUT", "http-sender-httpMethod-PUT.chain.cip.yaml"), "utf-8"),
    );
    expect(written).toEqual(generate(SENDER_PUT));
  });

  test("a value declared twice is refused", async () => {
    await expect(writeAxisFixtures([SENDER_PUT, SENDER_PUT], test.info().outputPath("axes"))).rejects.toThrow(
      /http-sender-httpMethod-PUT is declared twice/,
    );
  });

  test("a fixture name present in two corpus directories is refused", async () => {
    const dir = test.info().outputPath("axes");
    fs.mkdirSync(path.join(dir, "http-echo"), { recursive: true });
    expect(() => corpusFixtures([CHAIN_FIXTURE_DIR, dir])).toThrow(/fixture http-echo exists in both/);
  });

  test("every declared axis fixture generates", async () => {
    // Written to a scratch directory: the seed owns `fixtures/axes/`, and a schema run must not
    // rewrite a corpus a kept stack is still addressing.
    const names = await writeAxisFixtures(axisFixtures, test.info().outputPath("axes"));
    expect(names).toHaveLength(axisFixtures.filter((each) => !each.handWritten).length);
  });
});
