/**
 * The two design surfaces: the sequence diagrams `chain-design-controller` generates, and the
 * markdown `detailed-design-controller` renders out of a FreeMarker template.
 *
 * Covers both families whole — four operations each. Both `GET` forms are
 * `@Deprecated(since = "24.3")` and both are still served, so both are driven here beside the
 * `POST` that replaced them.
 *
 * **Structure, never bytes.** A diagram is asserted through its participants, its groups and the
 * labels of its interaction lines, and a document through its headings and its tables. The
 * generator writes a trailing space after every PlantUML line and `; ` after every Mermaid one, and
 * flexmark reflows the markdown it is handed — none of which is a contract, and all of which a
 * byte comparison would pin.
 *
 * Five shapes measured rather than assumed:
 *
 * - **`SIMPLE` is a filter over elements, not a shorter diagram.**
 *   `DesignGeneratorService.SIMPLE_DIAGRAM_ELEMENT_EXCLUDE_SET` names eleven element types —
 *   `script`, `mapper`, `mapper-2`, `header-modification`, `log-record`, `xslt` and the file and
 *   sftp families — and drops exactly those lines. Everything else, the trigger group included, is
 *   identical to `FULL`.
 * - **An element with neither design parameters nor a design processor emits nothing at all**, and
 *   the generator answers 200 over it. So a missing converter cannot be caught by watching the
 *   status: the corpus case below reads the diagram's content for each family instead.
 * - **The snapshot form reads `snapshotId` and only `snapshotId`.** `chainId` decides the
 *   participant label and nothing else, so any chain's URL renders any snapshot. Not asserted:
 *   the UI opens the diagram of the chain it displays (#842, won't fix). A snapshot id nothing answers
 *   to is not a 404 either: it answers 200 with a diagram holding the chain participant and no
 *   interaction.
 * - **The template store rejects rather than upserts.** `createOrUpdateTemplate` refuses a name it
 *   already holds with a 400, so a run-token name cannot collide with another worker's and the
 *   refusal is itself worth pinning.
 * - **A deleted template stops generating, and an unknown template id is a 404.** This used to be a
 *   `test.fail()`: `deleteTemplates` removed the row and left the template in the FreeMarker loader
 *   it had been registered in, so `GET /templates/{id}` answered 404 while
 *   `GET /chains/{id}?templateId={id}` still rendered. #851 renders from the database and the
 *   built-in map instead, and both reads now answer 404 for an id the store does not hold.
 *
 * The corpus case **reads** the seeded chains and never writes to them: `api` declares
 * `dependencies: ["seed"]`, which is what makes reading them here safe at all.
 */
import { test, expect } from "../../support/fixtures.js";
import type { Catalog, SequenceDiagram } from "../../support/catalog.js";
import { readCorpusState } from "../../support/corpus.js";
import { tokenized } from "../../support/run.js";
import { ABSENT_UUID } from "../../support/absent.js";
import { axisFixtureName, axisFixtures, HTTP_METHODS, LOG_LEVELS } from "../../fixtures/axis-generator.js";

/** The PlantUML source of a diagram, which is the form every helper below reads. */
function plantuml(diagram: SequenceDiagram): string {
  return diagram.diagramSources.PLANT_UML;
}

/** Non-blank lines, trimmed. The generator pads every line, and the padding is not a contract. */
function lines(source: string): string[] {
  return source.split("\n").map((line) => line.trim()).filter((line) => line.length > 0);
}

/**
 * The labels of the interaction lines — the diagram's content with its layout removed.
 *
 * A PlantUML interaction is `"from" -> "to" : "label"`, and the label is the only part that carries
 * an element's name or a converter's fixed wording. Activation, deactivation and the block keywords
 * are read separately by `blocks` below.
 */
function labels(source: string): string[] {
  const found: string[] = [];
  for (const line of lines(source)) {
    const match = /^"[^"]+"\s+(->|-->)\s+"[^"]+"\s+:\s+"(.*)"$/.exec(line);
    if (match) found.push(match[2]);
  }
  return found;
}

/** The participants, by the display name each is declared with. */
function participants(source: string): string[] {
  return lines(source)
    .map((line) => /^participant\s+"(.+)"\s+as\s+\S+$/.exec(line))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => match[1]);
}

/** The `group "…"` titles — one per trigger, plus the ones a converter opens for itself. */
function groups(source: string): string[] {
  return lines(source)
    .map((line) => /^group\s+"(.+)"$/.exec(line))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => match[1]);
}

/**
 * The block keywords a converter opened, with their argument: `alt`, `else`, `loop`, `par`.
 *
 * Kept apart from `labels` because these are what the container converters emit — a `choice` writes
 * `alt`/`else` and never an interaction line — so a case about a container reads this and a case
 * about an element reads that. The argument is quoted in the source and unquoted here, the same way
 * `labels` and `groups` unquote theirs.
 */
function blocks(source: string): string[] {
  return lines(source)
    .map((line) => /^(alt|else|loop|par)\s+"(.*)"$/.exec(line))
    .filter((match): match is RegExpExecArray => match !== null)
    .map((match) => `${match[1]} ${match[2]}`);
}

/**
 * A chain of this case's own, in the worker folder, shaped so that it snapshots.
 *
 * The trigger's `contextPath` carries the run token for the same reason the corpus's does: two
 * chains sharing a path is a silent collision, and a snapshot is refused outright without one.
 * `script` and `header-modification` are here because they are the two families `SIMPLE` drops.
 */
async function designChain(
  catalog: Catalog,
  run: string,
  folderId: string,
  what: string,
): Promise<{ id: string; name: string }> {
  const name = tokenized(run, `design-${what}`);
  const chain = await catalog.createChain(name, folderId);
  const trigger = await catalog.createElement(chain.id, "http-trigger");
  await catalog.patchElementProperties(chain.id, trigger.id, { contextPath: name });
  const script = await catalog.createElement(chain.id, "script");
  const headers = await catalog.createElement(chain.id, "header-modification");
  await catalog.connectElements(chain.id, trigger.id, script.id);
  await catalog.connectElements(chain.id, script.id, headers.id);
  return { id: chain.id, name };
}

test("a chain design carries both languages and the chain's own structure", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await designChain(catalog, run, folder.id, "shape");

  const diagram = await catalog.chainDesign(chain.id);

  expect(diagram.chainId).toBe(chain.id);
  // Null on the chain form, and the serializer drops a null key rather than writing one.
  expect(diagram, "the chain form has no snapshot").not.toHaveProperty("snapshotId");
  expect(Object.keys(diagram.diagramSources).sort()).toEqual(["MERMAID", "PLANT_UML"]);

  const source = plantuml(diagram);
  expect(lines(source)[0]).toBe("@startuml");
  expect(lines(source).at(-1)).toBe("@enduml");
  // Mermaid is the same diagram in another dialect, and the dialect is the assertion: its own
  // preamble, and `;` as the line terminator where PlantUML has none.
  expect(lines(diagram.diagramSources.MERMAID)[0]).toBe("sequenceDiagram;");

  expect(participants(source)).toEqual([
    "Unknown external (via external route) service",
    `QIP chain: ${chain.name}`,
  ]);
  // One group per trigger, titled with the trigger's name.
  expect(groups(source)).toEqual(["HTTP Trigger"]);
  expect(labels(source)).toEqual([
    `HTTP request to ${chain.name}\\nallowed methods=[ALL]`,
    "Script",
    "Header Modification",
    "Response",
  ]);
});

test("POST answers one diagram per mode, and SIMPLE drops the excluded families", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await designChain(catalog, run, folder.id, "modes");

  const both = await catalog.chainDesigns(chain.id, ["FULL", "SIMPLE"]);
  expect(Object.keys(both).sort()).toEqual(["FULL", "SIMPLE"]);

  const full = both.FULL!;
  const simple = both.SIMPLE!;
  expect(full.chainId).toBe(chain.id);
  expect(simple.chainId).toBe(chain.id);

  // `script` and `header-modification` are two of the eleven types SIMPLE excludes; the trigger and
  // the response are not, so the frame of the diagram is unchanged and only the two lines go.
  expect(labels(plantuml(full))).toContain("Script");
  expect(labels(plantuml(full))).toContain("Header Modification");
  expect(labels(plantuml(simple))).not.toContain("Script");
  expect(labels(plantuml(simple))).not.toContain("Header Modification");
  expect(groups(plantuml(simple))).toEqual(groups(plantuml(full)));
  expect(participants(plantuml(simple))).toEqual(participants(plantuml(full)));

  // The deprecated GET is the POST's FULL entry: one generator, two mappings.
  expect(plantuml(await catalog.chainDesign(chain.id))).toBe(plantuml(full));

  // An empty mode list is not a default: the loop runs zero times and the map comes back empty.
  expect(await catalog.chainDesigns(chain.id, [])).toEqual({});

  const missing = await catalog.raw("get", `/v1/design-generator/chains/${ABSENT_UUID}`);
  expect(missing.status()).toBe(404);
  expect(await missing.json()).toMatchObject({
    serviceName: "Catalog",
    errorMessage: `Can't find chain with id: ${ABSENT_UUID}`,
  });
});

test("the design request body has two shapes the generator answers 500 to", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await designChain(catalog, run, folder.id, "body");
  const path = `/v1/design-generator/chains/${chain.id}`;

  // Both 500s are filed in `docs/product-defects.md` under "The design generator 500s on two shapes
  // of its own request body". They are pinned as the measured contract rather than carried as
  // `test.fail()`, because neither has a correct answer this spec could assert instead: the
  // controller has no `@Valid` on the request and no schema says which status a rejected body owes.
  // The day either is fixed this case goes red and names the endpoint.
  const noModes = await catalog.raw("post", path, {});
  expect(noModes.status(), "an omitted diagramModes is a null dereference").toBe(500);
  expect(await noModes.json()).toMatchObject({
    serviceName: "Catalog",
    errorMessage: 'Cannot invoke "java.util.List.iterator()" because "modes" is null',
  });

  // `READ_UNKNOWN_ENUM_VALUES_AS_NULL` is enabled (`MapperAutoConfiguration.java:63`), so an
  // unknown mode deserializes to null, the generator keys the result map on null, and Jackson
  // refuses to write a null map key. The failure is on the way **out**, which is why this one
  // carries Spring's own problem-detail shape rather than the catalog's error envelope.
  const badMode = await catalog.raw("post", path, { diagramModes: ["NOPE"] });
  expect(badMode.status(), "an unknown mode becomes a null map key").toBe(500);
  expect(await badMode.json()).toMatchObject({
    type: "about:blank",
    status: 500,
    detail: "Failed to write request",
  });

  // A zero-byte body is refused correctly, which is what makes the two above defects rather than a
  // policy: the same endpoint does answer 400 when Spring can see there is nothing to read.
  const empty = await catalog.upload("post", path, {
    headers: { "content-type": "application/json" },
  });
  expect(empty.status()).toBe(400);
});

test("a snapshot design reads the snapshot's elements, not the chain's", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await designChain(catalog, run, folder.id, "snapshot");
  const snapshot = await catalog.createSnapshot(chain.id);

  const taken = await catalog.snapshotDesign(chain.id, snapshot.id);
  expect(taken.chainId).toBe(chain.id);
  expect(taken.snapshotId, "the snapshot form echoes the snapshot it read").toBe(snapshot.id);
  expect(labels(plantuml(taken))).toContain("Script");

  // The chain moves on and the snapshot does not. Deleting the script also cuts the edges it sat
  // on, so the header modification is left unreachable and leaves the chain's diagram with it —
  // which is the point: the two answers are now different documents.
  const script = (await catalog.listChainElements(chain.id)).find((each) => each.type === "script")!;
  await catalog.deleteElements(chain.id, [script.id]);

  expect(labels(plantuml(await catalog.chainDesign(chain.id))), "the chain lost the script").toEqual([
    `HTTP request to ${chain.name}\\nallowed methods=[ALL]`,
    "Response",
  ]);
  expect(
    labels(plantuml(await catalog.snapshotDesign(chain.id, snapshot.id))),
    "the snapshot still holds what the chain had when it was taken",
  ).toContain("Script");

  // The POST form takes the same modes, and SIMPLE filters the snapshot's elements the same way.
  const modes = await catalog.snapshotDesigns(chain.id, snapshot.id, ["FULL", "SIMPLE"]);
  expect(Object.keys(modes).sort()).toEqual(["FULL", "SIMPLE"]);
  expect(modes.FULL!.snapshotId).toBe(snapshot.id);
  expect(labels(plantuml(modes.FULL!))).toContain("Script");
  expect(labels(plantuml(modes.SIMPLE!))).not.toContain("Script");

  // A snapshot id nothing answers to is **not** a 404: `findAllBySnapshotId` answers an empty list
  // and the generator draws the chain participant over it.
  const nothing = await catalog.snapshotDesign(chain.id, ABSENT_UUID);
  expect(nothing.snapshotId).toBe(ABSENT_UUID);
  expect(labels(plantuml(nothing))).toEqual([]);
  expect(participants(plantuml(nothing))).toEqual([`QIP chain: ${chain.name}`]);
});

test("the detailed design renders the built-in template over the chain", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await designChain(catalog, run, folder.id, "dds");

  const design = await catalog.detailedDesign(chain.id, "default");
  expect(Object.keys(design).sort()).toEqual([
    "document",
    "simpleSeqDiagramMermaid",
    "simpleSeqDiagramPlantuml",
    "triggerSpecifications",
  ]);

  // Structure: the headings the built-in template writes, and the chain's own section under them.
  const headings = design.document.split("\n").filter((line) => line.startsWith("#"));
  expect(headings).toContain("# Integration Scenarios");
  expect(headings).toContain("# Security");
  expect(headings, "the chain gets a section of its own").toContain(`## ${chain.name}`);

  // The security matrix is one row per HTTP trigger, and it reads the trigger's access control.
  const matrix = design.document
    .split("\n")
    .filter((line) => line.startsWith("| HTTP Trigger "))
    .map((line) => line.split("|").map((cell) => cell.trim()));
  expect(matrix).toHaveLength(1);
  expect(matrix[0].slice(1, 4)).toEqual(["HTTP Trigger", "NONE", "Any role"]);

  // The two diagram fields are the generator's `SIMPLE` view of the same chain, byte for byte:
  // `TemplateDataBuilder` calls the same service rather than formatting one of its own.
  const simple = (await catalog.chainDesigns(chain.id, ["SIMPLE"])).SIMPLE!;
  expect(design.simpleSeqDiagramPlantuml).toBe(simple.diagramSources.PLANT_UML);
  expect(design.simpleSeqDiagramMermaid).toBe(simple.diagramSources.MERMAID);

  // Empty for a chain whose trigger implements no specification: `collectImplementedSpecs` selects
  // an `http-trigger` whose `systemType` is `IMPLEMENTED` and which names an operation.
  expect(design.triggerSpecifications).toEqual([]);

  const missing = await catalog.raw(
    "get",
    `/v1/detailed-design/chains/${ABSENT_UUID}?templateId=default`,
  );
  expect(missing.status()).toBe(404);
  expect(await missing.json()).toMatchObject({
    errorMessage: `Can't find chain with id: ${ABSENT_UUID}`,
  });

  // A template id nothing answers to is a 404, the same answer reading the template gives. Since
  // #851 the render resolves the template through one lookup against the database and the built-in
  // map, so an unknown id fails in that lookup rather than inside FreeMarker.
  const unknownTemplate = await catalog.raw(
    "get",
    `/v1/detailed-design/chains/${chain.id}?templateId=e2e-no-such-template`,
  );
  expect(unknownTemplate.status()).toBe(404);
  expect((await unknownTemplate.json()).errorMessage).toContain(
    "Detailed design template not found: e2e-no-such-template",
  );

  // `templateId` is a required request parameter, so omitting it never reaches the service and
  // carries Spring's problem-detail shape rather than the catalog's envelope.
  const noTemplate = await catalog.raw("get", `/v1/detailed-design/chains/${chain.id}`);
  expect(noTemplate.status()).toBe(400);
  expect(await noTemplate.json()).toMatchObject({
    type: "about:blank",
    detail: "Required parameter 'templateId' is not present.",
  });
});

test("a custom template is created, listed, read back, rendered and deleted", { tag: ["@catalog", "@tier1"] }, async ({ catalog, folder, run }) => {
  const chain = await designChain(catalog, run, folder.id, "template");
  const name = tokenized(run, "template");
  const content = "# Rendered\n\nChain: ${chain.name}\n";

  const created = await catalog.createDesignTemplate(name, content);
  // The id is the name lowercased (`buildTemplateId`), and the run token is lowercase already, so
  // the two are equal here — asserted against the transformation rather than against the name.
  expect(created.id).toBe(name.toLowerCase());
  expect(created.name).toBe(name);
  expect(created.content).toBe(content);
  expect(typeof created.createdWhen, "an epoch-millis stamp, not an ISO string").toBe("number");
  expect(created, "only the listing writes builtIn").not.toHaveProperty("builtIn");

  const read = await catalog.getDesignTemplate(created.id);
  expect(read).toEqual(created);

  // Scoped to this spec's own row and to the built-in beside it: every worker's templates are in
  // this listing, so nothing here asserts its length.
  const listed = await catalog.listDesignTemplates();
  const mine = listed.find((each) => each.id === created.id);
  expect(mine, "a custom template is listed").toBeDefined();
  expect(mine!.builtIn).toBe(false);
  expect(mine!.content).toBe(content);
  const builtIn = listed.find((each) => each.id === "default");
  expect(builtIn, "the built-in template is listed beside the custom ones").toBeDefined();
  expect(builtIn!.builtIn).toBe(true);

  // `includeContent=false` nulls the content, and the serializer then drops the key — for the
  // built-in rows as well as the custom ones.
  const light = await catalog.listDesignTemplates(false);
  expect(light.find((each) => each.id === created.id)).not.toHaveProperty("content");
  expect(light.find((each) => each.id === "default")).not.toHaveProperty("content");
  expect(light.find((each) => each.id === created.id)!.name).toBe(name);

  const rendered = await catalog.detailedDesign(chain.id, created.id);
  expect(rendered.document.trim()).toBe(`# Rendered\n\nChain: ${chain.name}`);
  // The diagrams are built whatever the template asks for: they are response fields, not template
  // output, so a template that mentions neither still comes back with both.
  expect(rendered.simpleSeqDiagramPlantuml).toContain("@startuml");

  await catalog.deleteDesignTemplates([created.id]);
  const gone = await catalog.raw("get", `/v1/detailed-design/templates/${created.id}`);
  expect(gone.status()).toBe(404);
  expect(await gone.json()).toMatchObject({
    errorMessage: `Detailed design template not found: ${created.id}`,
  });
  expect(
    (await catalog.listDesignTemplates(false)).map((each) => each.id),
    "the listing loses it too",
  ).not.toContain(created.id);
});

test("the template store refuses a duplicate, a malformed name, and a built-in's", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "refusals");
  const created = await catalog.createDesignTemplate(name, "# One\n");

  try {
    // The endpoint is `PUT` and the service is called `createOrUpdateTemplate`, and it does
    // neither: a name the store already holds is refused. That is what makes a run-token name safe
    // to mint in a parallel project — two workers cannot silently overwrite one another.
    const duplicate = await catalog.raw("put", "/v1/detailed-design/templates", {
      name,
      content: "# Two\n",
    });
    expect(duplicate.status()).toBe(400);
    expect(await duplicate.json()).toMatchObject({
      serviceName: "Catalog",
      errorMessage: "Template name is not unique",
    });
    expect(
      (await catalog.getDesignTemplate(created.id)).content,
      "the refused write left the first content in place",
    ).toBe("# One\n");

    const malformed = await catalog.raw("put", "/v1/detailed-design/templates", {
      name: `${name} two`,
      content: "# x\n",
    });
    expect(malformed.status()).toBe(400);
    expect((await malformed.json()).errorMessage).toBe(
      `Invalid template name format: ${name} two, must match the pattern: ^[a-zA-Z0-9_.-]+$`,
    );

    // The id is the name lowercased, so a name differing from a built-in's only in case collides
    // with it — and the collision is a 409 whose message is the id and nothing else.
    const shadow = await catalog.raw("put", "/v1/detailed-design/templates", {
      name: "Default",
      content: "# x\n",
    });
    expect(shadow.status()).toBe(409);
    expect(await shadow.json()).toMatchObject({ serviceName: "Catalog", errorMessage: "default" });
    expect(
      (await catalog.getDesignTemplate("default")).name,
      "the built-in template is untouched",
    ).toBe("Default");
  } finally {
    // `.catch` for the same reason every sibling teardown has one: a cleanup that
    // failed must not replace the assertion that failed first.
    await catalog.deleteDesignTemplates([created.id]).catch(() => {});
  }

  // The delete is by an explicit id list. An id nothing answers to is not an error, and no id at
  // all is: `ids` is required, so "delete none" cannot be expressed.
  const unknown = await catalog.raw("delete", "/v1/detailed-design/templates?ids=e2e-no-such-id");
  expect(unknown.status()).toBe(204);
  const noIds = await catalog.raw("delete", "/v1/detailed-design/templates");
  expect(noIds.status()).toBe(400);
});

test("a deleted template stops rendering", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  const chain = await designChain(catalog, run, folder.id, "ghost");
  const name = tokenized(run, "ghost");
  const created = await catalog.createDesignTemplate(name, "# Ghost\n");
  await catalog.deleteDesignTemplates([created.id]);

  // This case pinned a defect with `test.fail()` until #851: `deleteTemplates` removed the row and
  // left the template in the `StringTemplateLoader` it had been put into, so the loader and the
  // table diverged for the life of the process and the render went on answering 200. The render now
  // reads the database and the built-in map directly, and a deleted id answers 404 like every other
  // unknown one.
  const render = await catalog.raw(
    "get",
    `/v1/detailed-design/chains/${chain.id}?templateId=${created.id}`,
  );
  expect(render.status(), "a template the store answers 404 for should not still render a document").toBe(404);
  expect((await render.json()).errorMessage).toContain(`Detailed design template not found: ${created.id}`);
});

/**
 * What each seeded fixture's converter puts on the diagram, and which family emits it.
 *
 * A missing converter fails nothing: an element with neither `designParameters` nor a
 * `DesignProcessor` is skipped and the request still answers 200 — so the check has to read the
 * diagram rather than the status, and it reads one marker per fixture.
 *
 * The markers split by where they come from. `##{ELEMENT_NAME_REF}` families — `script`,
 * `header-modification`, `mapper-2` and the container children — put the **element's own name** on
 * the line, so those markers are fixture names. The rest are fixed wording a Java processor writes.
 */
interface CorpusMarkers {
  /** Interaction-line labels, matched as substrings. */
  labels?: string[];
  /** `alt` / `else` / `loop` / `par` openers, matched by prefix. */
  blocks?: string[];
  /** `group "…"` titles beyond the trigger group every chain has, matched by prefix. */
  groups?: string[];
  /** Participants, matched whole. */
  participants?: string[];
  /** The trigger group's title, when the trigger is not named `HTTP Trigger`. */
  trigger?: string;
}

const CORPUS_MARKERS: Record<string, CorpusMarkers> = {
  // `ChainCall2DesignProcessor` resolves `chainCallElementId` to the element it addresses and names
  // it, which is the callee's `chain-trigger-2`.
  "chain-call": { labels: ["QIP chain trigger call: Chain Trigger"] },
  // `chain-trigger-2` carries `requestLineTitle: 'QIP chain call'` and a fixed participant name in
  // its descriptor, so a chain reached from another chain is drawn as coming from nowhere in
  // particular. Its two triggers give it two groups.
  "chain-callee": {
    labels: ["QIP chain call", "Direct Reply", "Callee Reply"],
    groups: ["Chain Trigger"],
    participants: ["Unknown QIP chain"],
  },
  // `CheckpointDesignProcessor` opens a group of its own and writes both branches of the retry.
  checkpoint: {
    labels: ["Save context", "Load context", "After Checkpoint"],
    blocks: ["alt Trigger", "else Checkpoint"],
    groups: ["Checkpoint with id "],
    participants: ["Unknown user"],
  },
  // `CheckpointDesignProcessor` draws each checkpoint as its own group.
  "checkpoint-retry": {
    labels: ["Between Checkpoints", "Fail On Flag", "Load context", "Save context"],
    groups: ["First Checkpoint with id ", "Second Checkpoint with id "],
    participants: ["Unknown user"],
  },
  // A container's `designContainerParameters` substitutes the child's name and its condition.
  choice: {
    labels: ["When Branch", "Otherwise Branch"],
    blocks: ["alt When, on condition: ", "else Otherwise"],
  },
  // `circuit-breaker-2` opens its block with the configuration's threshold.
  "circuit-breaker-count-based": { labels: ["Main Script", "Fallback Script"], blocks: ["alt Failure rate < 60%", "else On Fallback"] },
  "circuit-breaker-time-based": { labels: ["Main Script", "Fallback Script"], blocks: ["alt Failure rate < 60%", "else On Fallback"] },
  condition: {
    labels: ["If Branch", "Else Branch"],
    blocks: ["alt If, on condition: ", "else Else"],
  },
  // The blocks follow document order, not the priority the engine routes by.
  "condition-branches": {
    labels: ["Broad Branch", "Narrow Branch", "Else Branch"],
    blocks: ["alt Broad If, on condition: ", "else Narrow If, on condition: ", "else Else"],
  },
  "context-propagation": { labels: ["Write Context", "Header Modification", "Read Context"] },
  // The file elements are drawn with the file name as written, placeholder and all.
  "file-read": { labels: ["Read local file: e2e-"] },
  "file-write": { labels: ["Write to local file: e2e-"] },
  // A generated sender chain is drawn as a call to a participant named after its URI.
  ...Object.fromEntries(
    [true, false].map((value) => [
      axisFixtureName({ family: "graphql-sender", axisPath: "propagateContext", value }),
      { labels: ["GraphQL request (query/mutation) to http://localhost:8080/routes/e2e-"] },
    ]),
  ),
  "http-echo": { labels: ["Header Modification"] },
  // `HttpSenderDesignProcessor` draws the call out to a participant named from the sender's URI.
  // `Internal service` rather than `External`: the fixture addresses a service inside the stack.
  "http-out": {
    labels: ["GET, /actuator/health"],
    participants: ["Internal service: http://qip-runtime-catalog:8080"],
  },
  ...Object.fromEntries([
    ...HTTP_METHODS.map((value) => [
      axisFixtureName({ family: "http-sender", axisPath: "httpMethod", value }),
      { labels: [`${value}, /routes/e2e-`], participants: ["Internal service: http://localhost:8080"] },
    ]),
    ...[true, false].map((value) => [
      axisFixtureName({ family: "http-sender", axisPath: "propagateContext", value }),
      { labels: ["GET, /orders"], participants: ["Internal service: http://e2e-http-sender.invalid:8080"] },
    ]),
  ]),
  // A generated http-trigger axis chain names its trigger after the branch, and the handlers are
  // not drawn: only the step after the trigger is.
  ...Object.fromEntries(
    axisFixtures
      .filter((fixture) => fixture.family === "http-trigger")
      .map((fixture) => [
        axisFixtureName(fixture),
        { trigger: `${fixture.axisPath}=${String(fixture.value)}`, ...(fixture.downstream ? { labels: [fixture.downstream.name] } : {}) },
      ]),
  ),
  // A generated log record is drawn as a line named after the branch.
  ...Object.fromEntries(
    LOG_LEVELS.map((value) => [axisFixtureName({ family: "log-record", axisPath: "logLevel", value }), { labels: [`logLevel=${value}`] }]),
  ),
  "long-running": { labels: ["Hold"] },
  // `LoopContainerDesignProcessor` keys the block on the loop's own `expression` property, so the
  // marker is the fixture's value rather than the placeholder.
  loop: { labels: ["Iteration Script", "Report"], blocks: ["loop 3"] },
  mapper: { labels: ["Mapper"] },
  masking: { labels: ["Echo"] },
  "method-echo": { labels: ["Echo Method"] },
  "placeholder-resolution": { labels: ["Resolve Placeholder"] },
  // The reference renders the reused block inline where the reference sits, which is why the
  // reused script appears and the reference itself never does.
  reuse: { labels: ["Reused Script", "Report"] },
  script: { labels: ["Script"] },
  // The script corpus under `fixtures/script/`, drawn like any other script: one line per
  // element, under the name the fixture gave it. The `.yaml` is part of the fixture name, because
  // these are a file per chain rather than a directory per chain.
  "script-empty.yaml": { labels: ["Empty Script"] },
  // The failure corpus. The three chains that cannot deploy are spec-owned and never seeded, so
  // they have no row here; these three are ordinary corpus chains that happen to fail at run time.
  "script-failures-caught.yaml": {
    labels: ["Dereference Null", "Report Caught", "Finally Branch"],
    blocks: ["alt Try", "else Catch, on exception: java.lang.Exception", "else Finally"],
  },
  "script-failures-npe.yaml": { labels: ["Null Property"] },
  "script-failures-uncaught.yaml": { labels: ["Throw Uncaught"] },
  "script-exchange-body.yaml": {
    labels: ["Return Only", "Read Inbound", "As Bytes", "As Stream", "As Map", "As List", "Report"],
  },
  "script-exchange-headers.yaml": { labels: ["Change Headers", "Read Headers"] },
  "script-exchange-null-body.yaml": { labels: ["Drop Body", "Read Null Body"] },
  "script-exchange-properties.yaml": { labels: ["Write Property", "Header Modification", "Read Property"] },
  // A script inside a container is drawn inside the container's own block, so these two carry the
  // shapes of `loop` and `split` around a line of their own.
  "script-in-container-loop.yaml": { labels: ["Loop Pass", "Report Passes"], blocks: ["loop 3"] },
  "script-in-container-split.yaml": {
    labels: ["Seed Split", "Main Pass", "First Pass", "Second Pass"],
    blocks: ["par main", "else first", "else second"],
  },
  "script-libraries-datetime.yaml": { labels: ["Use Datetime"] },
  "script-libraries-jdk.yaml": { labels: ["Use Jdk"] },
  "script-libraries-json.yaml": { labels: ["Slurp Json", "Write Json"] },
  "script-libraries-jsr223.yaml": { labels: ["Use Jsr223"] },
  "script-libraries-nio.yaml": { labels: ["Use Nio"] },
  "script-libraries-sql.yaml": { labels: ["Use Sql"] },
  "script-libraries-xml.yaml": { labels: ["Slurp Xml", "Parse Xml"] },
  // `split-2` keys its branches on `splitName`, not on the element name, so these are lowercase.
  split: {
    labels: ["Main Script", "Aux Script", "Waiting for 'split elements' to complete"],
    blocks: ["par main", "else aux"],
  },
  "split-async": { labels: ["First Async Script", "Second Async Script", "After Split"], blocks: ["par First Async Branch", "else Second Async Branch"] },
  "try-catch-finally": {
    labels: ["Throwing Script", "Catch Branch", "Finally Branch"],
    blocks: ["alt Try", "else Catch, on exception: java.lang.Exception", "else Finally"],
  },
  "try-catch-finally-branches": {
    labels: ["Try Script", "Runtime Catch Branch", "Argument Catch Branch", "Finally Branch"],
    blocks: [
      "alt Try",
      "else Runtime Catch, on exception: java.lang.RuntimeException",
      "else Argument Catch, on exception: java.lang.IllegalArgumentException",
      "else Finally",
    ],
  },
  // `xslt` is drawn under its element name, and the stylesheet write as a file write.
  xslt: { labels: ["Keep Request", "Write to local file: e2e-", "Restore Request", "XSLT"] },
};

test("every seeded fixture generates a diagram carrying its own converter's output", { tag: ["@catalog", "@tier1"] }, async ({ catalog }) => {
  // Reads the corpus and writes nothing to it. `api` declares `dependencies: ["seed"]`, which is
  // what makes the chains here exist by the time this case runs.
  const corpus = readCorpusState();
  expect(
    corpus.chains.map((each) => each.fixture).sort(),
    "the marker table is one row per fixture, so a new fixture lands here as a failure",
  ).toEqual(Object.keys(CORPUS_MARKERS).sort());

  for (const chain of corpus.chains) {
    const expected = CORPUS_MARKERS[chain.fixture];
    const diagram = (await catalog.chainDesigns(chain.id, ["FULL"])).FULL!;
    const source = plantuml(diagram);
    // One parse of each shape for the whole chain, rather than one per marker loop.
    const found = labels(source);
    const opened = blocks(source);
    const openedGroups = groups(source);
    const drawn = participants(source);

    expect(diagram.chainId, `${chain.fixture}: the diagram names its chain`).toBe(chain.id);
    expect(lines(source).at(-1), `${chain.fixture}: the document is closed`).toBe("@enduml");
    expect(drawn, `${chain.fixture}: the chain itself is always a participant`).toContain(
      `QIP chain: ${chain.name}`,
    );
    expect(openedGroups, `${chain.fixture}: a group per trigger`).toContain(expected.trigger ?? "HTTP Trigger");

    for (const label of expected.labels ?? []) {
      expect(found.some((each) => each.includes(label)), `${chain.fixture}: no line said ${label}`).toBe(true);
    }
    for (const block of expected.blocks ?? []) {
      expect(opened.some((each) => each.startsWith(block)), `${chain.fixture}: no block said ${block}`).toBe(true);
    }
    for (const group of expected.groups ?? []) {
      expect(openedGroups.some((each) => each.startsWith(group)), `${chain.fixture}: no group said ${group}`).toBe(true);
    }
    for (const participant of expected.participants ?? []) {
      expect(drawn, `${chain.fixture}: participant`).toContain(participant);
    }
  }
});
