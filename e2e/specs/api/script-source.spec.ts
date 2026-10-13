/**
 * Where a Script element keeps its source, and what a round trip does to it.
 *
 * `resource-reference-script.schema.yaml` gives the element four properties, and only one of them
 * is a runtime concern. `script` is the source. The other three — `exportFileExtension: groovy`,
 * `propertiesToExportInSeparateFile: script` and `propertiesFilename` — describe an **archive**:
 * `ChainElementFilePropertiesSubstitutor` lifts `script` out into its own `.groovy` file at export
 * and writes the file name back into `propertiesFilename`, and `ChainElementPropertiesSubstitutor`
 * reads the file back into `script` and removes `propertiesFilename` at import. So a chain on the
 * platform always carries the script inline, whatever its archive looked like, and "inline and
 * separate-file both run the same" is a tautology rather than an axis to sweep. That is what this
 * spec asserts, and it is why the registry has no `propertiesFilename` row.
 *
 * The legacy export flag is deliberately not here. Turning `CIP_EXPORT_LEGACY_FORMAT` on needs
 * `env.restartWith`, and a spec that restarts a service belongs in `specs/env/`; the frozen corpus
 * under `fixtures/archives/frozen/` is what pins the older document shapes.
 *
 * The chain is created and destroyed by this file. It carries a fixed id, so the delete in front of
 * the import is load-bearing twice over: an import over a live id is an update, and this fixture is
 * imported three times in one case.
 */
import { test, expect } from "../../support/fixtures.js";
import { entryNames, entryText } from "../../support/zip.js";
import { assembleFixture, readDocumentFixture, SCRIPT_FIXTURE_DIR } from "../../fixtures/templating.js";
import { chainEntry, chainExists, importChains } from "../../support/chain-import.js";

const FIXTURE = "script-inline.yaml";

/**
 * The exporter's name for the file it lifts `script` into.
 *
 * `generatePropertiesFileName` builds `<elementId>.element.<kind>.cip.<extension>`, and the archive
 * puts it under a `resources/` directory of the chain's own — measured, not read off the name
 * generator, which knows nothing about the directory.
 *
 * This is the layout `cip.export.legacy-resource-names=false` produces, which is the default since
 * #640. `OldElementResourceFileNameBuilder` still writes the previous `script-<elementId>.groovy`
 * behind `CIP_EXPORT_LEGACY_RESOURCE_NAMES=true`, and that leg needs `env.restartWith`, so it
 * belongs in `specs/env/` rather than here.
 */
function scriptResourceName(elementId: string): string {
  return `${elementId}.element.script.cip.groovy`;
}

/** The same name as the archive entry it is written under, so the two cannot drift apart. */
function scriptEntry(chainId: string, elementId: string): string {
  return `chains/${chainId}/resources/${scriptResourceName(elementId)}`;
}

test("a script exports into its own .groovy file and imports back inline", { tag: ["@catalog", "@tier1"] }, async ({ catalog, run }) => {
  const fixture = readDocumentFixture(FIXTURE, run, SCRIPT_FIXTURE_DIR);
  const source = (
    ((fixture.document.content as Record<string, unknown>).elements as Array<Record<string, unknown>>)
      .find((element) => element.type === "script")!.properties as Record<string, string>
  ).script;

  // Residue from a run that died before its teardown would make the first import an update, and
  // `CREATED` below is the assertion that says the fixture was written rather than matched.
  await catalog.raw("delete", `/v1/chains/${fixture.id}`);
  const created = await importChains(catalog, await assembleFixture(FIXTURE, run, SCRIPT_FIXTURE_DIR));
  expect(created.status).toBe(200);
  expect(created.body.chains).toEqual([
    expect.objectContaining({ id: fixture.id, status: "CREATED" }),
  ]);

  try {
    const elements = await catalog.listChainElements(fixture.id);
    const script = elements.find((element) => element.type === "script")!;
    // The imported element carries the source inline and no file reference at all: the fixture
    // never shipped one, and nothing on the platform side invents one.
    expect(script.properties.script).toBe(source);
    expect(script.properties.propertiesFilename).toBeUndefined();

    const archive = await catalog.exportChains([fixture.id]);
    // The full paths, not the basenames. A root-layout archive has the same basenames and imports
    // nothing, so a basename assertion is green over the failure it exists to catch.
    expect(await entryNames(archive)).toEqual(
      [chainEntry(fixture.id), scriptEntry(fixture.id, script.id)].sort(),
    );
    // The lifted file is the script, byte for byte — not a re-indented or re-quoted copy of it.
    expect(await entryText(archive, scriptEntry(fixture.id, script.id))).toBe(source);

    const exported = await entryText(archive, chainEntry(fixture.id));
    expect(exported).toContain(`propertiesFilename: "${scriptResourceName(script.id)}"`);
    // The source moved rather than being copied. Were it in both places, an import reading the
    // file back would prove nothing about the file.
    expect(exported).not.toContain("script: ");

    await catalog.deleteChain(fixture.id);
    expect(await chainExists(catalog, fixture.id)).toBe(false);

    // The original bytes. Re-zipping an unpacked tree puts the entries at the archive root, which
    // imports nothing and answers 200.
    const restored = await importChains(catalog, archive);
    expect(restored.status).toBe(200);
    expect(restored.body.chains).toEqual([
      expect.objectContaining({ id: fixture.id, status: "CREATED" }),
    ]);

    const restoredElements = await catalog.listChainElements(fixture.id);
    const restoredScript = restoredElements.find((element) => element.type === "script")!;
    expect(restoredScript.id).toBe(script.id);
    // The round trip's whole claim: the text came back, and it came back inline. The element the
    // engine would compile is the one the fixture wrote, with the archive's file reference gone.
    expect(restoredScript.properties.script).toBe(source);
    expect(restoredScript.properties.propertiesFilename).toBeUndefined();

    // And the layout survives a second export, so the round trip is repeatable rather than a
    // one-way flattening that happens to read back once.
    const again = await catalog.exportChains([fixture.id]);
    expect(await entryNames(again)).toEqual(
      [chainEntry(fixture.id), scriptEntry(fixture.id, script.id)].sort(),
    );
    expect(await entryText(again, scriptEntry(fixture.id, script.id))).toBe(source);
  } finally {
    await catalog.raw("delete", `/v1/chains/${fixture.id}`);
  }
});
