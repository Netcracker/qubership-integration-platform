/**
 * Common and secured variables: the two families a chain reads its configuration from.
 *
 * **Which version.** Measured on this stack, the whole `/v1/secured-variables` surface answers
 * **410 `Default secret functionality is disabled`** — every one of its five operations, the import
 * included. It addresses the default secret, `qip-secured-variables-v2`, and
 * `DefaultSecretPolicyService` gates that behind `cip.variables.default-secret.enabled`, which
 * defaults to false and is not set here. So the v1 secured surface cannot be covered as configured,
 * and the case below pins the 410 rather than pretending otherwise: it is the operation's contract
 * on this configuration, and a stack that enables the flag turns it red on purpose.
 * Secured behavior is therefore exercised through `/v2/secured-variables`, which addresses a
 * **named** secret. The CRUD case below is what carries the registry rows for that controller: it
 * asserts the create, both reads, the update and the delete, so those rows are `covered` here.
 * Common variables have no such split: `/v1/common-variables` is live and is what these cases use.
 *
 * **What a secured variable makes observable.** Nothing but its name. `GET /v2/secured-variables`
 * answers names per secret and there is no read-back of a value anywhere in the API, so a case
 * asserting that a value was stored correctly cannot fail and is not written. What is asserted is
 * the name appearing, surviving an update, and going away — plus that no answer on the path ever
 * carries the value.
 *
 * **Import and export are asymmetric between the two families.** Common variables export as YAML or
 * as an archive and import back from either; secured variables have no export at all — there is
 * nothing readable to write — and their only import is the v1 one, which is behind the same 410.
 *
 * Four more measured shapes the cases pin, none of them guessable from the endpoint names:
 *
 * - `PATCH /v1/common-variables/{name}` is an **upsert**, and it answers `{response: name}` — the
 *   name, not the value. A caller reading that as a value round trip reads the name back and passes.
 *   With no body at all it answers 200 and stores `""`.
 * - The export **excludes the platform's own variables**. `namespace` and `tenant_id` are in
 *   `DEFAULT_VARIABLES_LIST` and are filtered out, so an export naming only those has nothing to
 *   write and answers **204**.
 * - An export naming a variable that does not exist answers **400 `Consul txn request failed with
 *   code 409 conflict`** — the store's own error, surfaced verbatim. It is pinned as measured
 *   because the alternative is to assert nothing; the leaked message is worth reporting separately.
 * - The preview reports `currentValue` as `""` for a variable that does not exist yet, not as null
 *   and not by omitting the field.
 *
 * Common variables are global — one Consul KV tree for the whole stack — so every case here asserts
 * over the names it created and never over the listing as a whole.
 */
import JSZip from "jszip";
import { test, expect, findResidue } from "../../support/fixtures.js";
import { SECRET_FIXTURE_NAME, tokenized } from "../../support/run.js";
import { ensureSecretFixture } from "../../support/secret-fixture.js";

/** The file both the import and the preview take, written the way the export writes it. */
function variablesFile(variables: Record<string, string>): Buffer {
  const lines = Object.entries(variables).map(([name, value]) => `${name}: ${value}`);
  return Buffer.from(`---\n${lines.join("\n")}\n`, "utf-8");
}

test("a common variable round-trips through create, list, update, and delete", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const first = tokenized(run, "cv-first");
  const second = tokenized(run, "cv-second");

  const created = await catalog.addCommonVariables({ [first]: "one", [second]: "two" });
  expect(created.sort(), "the create answers the names it wrote").toEqual([first, second].sort());

  try {
    const listed = await catalog.listCommonVariables();
    expect(listed[first]).toBe("one");
    expect(listed[second]).toBe("two");

    // `{response: name}`, and the name is the point: a spec expecting the new value here would
    // compare a name against a value and never notice the update had not happened.
    const updated = await catalog.updateCommonVariable(first, "one-updated");
    expect(updated.response).toBe(first);
    expect((await catalog.listCommonVariables())[first]).toBe("one-updated");

    const deleted = await catalog.raw("delete", `/v1/common-variables?variablesNames=${first},${second}`);
    expect(deleted.status(), "the delete answers 204").toBe(204);

    const after = await catalog.listCommonVariables();
    expect(Object.keys(after)).not.toContain(first);
    expect(Object.keys(after)).not.toContain(second);
    // The batch delete takes exact names and reports nothing about the ones it did not find.
    expect((await catalog.raw("delete", `/v1/common-variables?variablesNames=${first}`)).status()).toBe(204);
  } finally {
    // A common variable is a global Consul key that no folder cascade reaches, so a failure
    // anywhere above leaves these two on the stack for every later run to sweep.
    await catalog.deleteCommonVariables([first, second]).catch(() => {});
  }
});

test("the update is an upsert, and an empty body stores an empty value", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "cv-upsert");
  try {
    // Nothing created it: the PATCH is the create. The endpoint's own description says "update or
    // add", and the suite depends on it — the seed writes single variables this way.
    expect((await catalog.updateCommonVariable(name, "made-by-patch")).response).toBe(name);
    expect((await catalog.listCommonVariables())[name]).toBe("made-by-patch");

    // No body at all: 200, and the value becomes the empty string rather than staying as it was.
    expect((await catalog.updateCommonVariable(name)).response).toBe(name);
    expect((await catalog.listCommonVariables())[name]).toBe("");
  } finally {
    await catalog.deleteCommonVariables([name]);
  }
});

test("a name outside the entity pattern is refused on both write paths, and nothing is created", { tag: ["@catalog", "@tier2"] }, async ({ catalog }) => {
  // Two different mechanisms answer here, which is why both are exercised: the map key is checked
  // by bean validation on the controller, the path variable by the service.
  const posted = await catalog.raw("post", "/v1/common-variables", { "bad name!": "v" });
  expect(posted.status()).toBe(400);
  // Read as JSON rather than as text: the pattern is quoted inside the message, so the raw body
  // carries it escaped and a substring match against the pattern as written never fires.
  const refused = (await posted.json()) as { errorMessage: string };
  expect(refused.errorMessage).toContain('does not match "^[-._a-zA-Z0-9]+$"');

  const patched = await catalog.raw("patch", "/v1/common-variables/bad%20name%21", "v");
  expect(patched.status()).toBe(400);
  expect(await patched.text()).toContain("Malformed variable name: bad name!");

  expect(Object.keys(await catalog.listCommonVariables())).not.toContain("bad name!");
});

test("the export writes a file the import reads back", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const first = tokenized(run, "cv-export-first");
  const second = tokenized(run, "cv-export-second");
  await catalog.addCommonVariables({ [first]: "alpha", [second]: "beta" });

  const exported = await catalog.exportCommonVariables([first, second]);
  expect(exported.status()).toBe(200);
  expect(exported.headers()["content-disposition"]).toContain("filename=common-variables.yaml");
  const body = Buffer.from(await exported.body());
  expect(body.toString("utf-8")).toContain(`${first}: alpha`);

  // Deleted, so the import has to be what puts them back. Without this step the round trip passes
  // over variables that never went away.
  await catalog.deleteCommonVariables([first, second]);
  expect(Object.keys(await catalog.listCommonVariables())).not.toContain(first);

  try {
    const imported = await catalog.importCommonVariables(body);
    expect(imported.map((each) => `${each.name}=${each.status}`).sort()).toEqual(
      [`${first}=CREATED`, `${second}=CREATED`].sort(),
    );
    const listed = await catalog.listCommonVariables();
    expect(listed[first]).toBe("alpha");
    expect(listed[second]).toBe("beta");

    // A second import of the same bytes reports UPDATED rather than refusing or duplicating. The
    // rows are named, not counted over: `every` on an empty array is true, and an import that
    // reported nothing is exactly the silent no-op this file's header warns about.
    const again = await catalog.importCommonVariables(body);
    expect(again.map((each) => `${each.name}=${each.status}`).sort()).toEqual(
      [`${first}=UPDATED`, `${second}=UPDATED`].sort(),
    );
  } finally {
    await catalog.deleteCommonVariables([first, second]);
  }
});

test("the archive form of the export holds the same file under variables/", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "cv-archive");
  await catalog.addCommonVariables({ [name]: "zipped" });
  try {
    const exported = await catalog.exportCommonVariables([name], true);
    expect(exported.status()).toBe(200);
    expect(exported.headers()["content-disposition"]).toContain("filename=common-variables.zip");

    const archive = await JSZip.loadAsync(Buffer.from(await exported.body()));
    // The entry path, not the basename: an archive with the file at its root is a different
    // artifact, and the import is what reads this layout.
    expect(Object.keys(archive.files)).toContain("variables/common-variables.yaml");
    expect(await archive.file("variables/common-variables.yaml")?.async("string")).toContain(
      `${name}: zipped`,
    );

    await catalog.deleteCommonVariables([name]);
    const imported = await catalog.importCommonVariables(Buffer.from(await exported.body()), {
      fileName: "common-variables.zip",
    });
    expect(imported.map((each) => each.name)).toEqual([name]);
    expect((await catalog.listCommonVariables())[name]).toBe("zipped");
  } finally {
    await catalog.deleteCommonVariables([name]);
  }
});

test("the export skips the platform's own variables and refuses a name that does not exist", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  // `namespace` and `tenant_id` are in DEFAULT_VARIABLES_LIST and are filtered out of every export,
  // so naming only those leaves nothing to write. 204 rather than an empty file.
  const defaults = await catalog.exportCommonVariables(["namespace", "tenant_id"]);
  expect(defaults.status()).toBe(204);
  expect((await defaults.body()).length).toBe(0);

  // The answer names the variable it could not find. Asserting the name and not only the status is
  // what separates this from a blanket 404: the export used to fail the whole request with Consul's
  // own 409 wording and said nothing about which variable was missing (#850).
  const missing = tokenized(run, "cv-never-created");
  const unknown = await catalog.exportCommonVariables([missing]);
  expect(unknown.status()).toBe(404);
  expect(await unknown.text()).toContain(`Can't find common variables: ${missing}`);
});

test("the preview reports the stored value beside the incoming one and writes nothing", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const existing = tokenized(run, "cv-preview-existing");
  const fresh = tokenized(run, "cv-preview-fresh");
  await catalog.addCommonVariables({ [existing]: "stored" });

  try {
    const preview = await catalog.previewCommonVariables(
      variablesFile({ [existing]: "incoming", [fresh]: "new-value" }),
    );
    const byName = new Map(preview.map((each) => [each.name, each]));
    expect(byName.get(existing)).toMatchObject({ value: "incoming", currentValue: "stored" });
    // `""` rather than null or an absent key, which is what a caller rendering a diff reads.
    expect(byName.get(fresh)).toMatchObject({ value: "new-value", currentValue: "" });

    const listed = await catalog.listCommonVariables();
    expect(listed[existing], "the preview must not write").toBe("stored");
    expect(Object.keys(listed)).not.toContain(fresh);
  } finally {
    await catalog.deleteCommonVariables([existing]);
  }
});

test("the import writes only the variables it is told to", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const wanted = tokenized(run, "cv-import-wanted");
  const ignored = tokenized(run, "cv-import-ignored");
  try {
    const imported = await catalog.importCommonVariables(
      variablesFile({ [wanted]: "kept", [ignored]: "dropped" }),
      { names: [wanted] },
    );
    expect(imported.map((each) => each.name)).toEqual([wanted]);

    const listed = await catalog.listCommonVariables();
    expect(listed[wanted]).toBe("kept");
    expect(Object.keys(listed), "a variable outside the filter is not imported").not.toContain(ignored);
  } finally {
    await catalog.deleteCommonVariables([wanted, ignored]);
  }
});

test("the v1 secured variable surface is gone while the default secret is disabled", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  const name = tokenized(run, "sv-v1");
  const gone = "Default secret functionality is disabled";

  // All five operations, not only the read: an earlier reading of this stack assumed the GET was
  // special and that a write would land somewhere. Every one of them addresses the default secret.
  const calls = [
    await catalog.raw("get", "/v1/secured-variables"),
    await catalog.raw("post", "/v1/secured-variables", { [name]: "value" }),
    await catalog.raw("patch", `/v1/secured-variables/${name}`, "value"),
    await catalog.raw("delete", `/v1/secured-variables?variablesNames=${name}`),
    // Through the client rather than the `request` fixture, multipart and all: a call made around
    // the client records nothing, and this one earns the operation's row.
    await catalog.upload("post", "/v1/secured-variables/import", {
      multipart: { file: { name: "secured.yaml", mimeType: "application/x-yaml", buffer: variablesFile({ [name]: "value" }) } },
    }),
  ];
  for (const call of calls) {
    expect(call.status(), `${call.url()} answers 410 while the default secret is disabled`).toBe(410);
    expect(await call.text()).toContain(gone);
  }

  // And the reason, as the v2 listing reports it: the default secret is there and is disabled.
  const secrets = await catalog.listSecrets();
  const fallback = secrets.find((secret) => secret.defaultSecret);
  expect(fallback, "the default secret is what the v1 surface addressed").toMatchObject({ disabled: true });
});

test("a secured variable is created, updated, and deleted in a named secret, and its value never comes back", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  // The one committed secret name the suite ever posts. It has to be created here rather than
  // inherited from another spec: the local store is a map inside the catalog process, so a restart
  // empties it and spec order guarantees nothing.
  await ensureSecretFixture(catalog);
  const name = tokenized(run, "sv-crud");
  const value = `secret-value-${run}`;

  try {
    const added = await catalog.addSecuredVariables(SECRET_FIXTURE_NAME, { [name]: value });
    const fixture = added.find((secret) => secret.secretName === SECRET_FIXTURE_NAME);
    expect(fixture?.variablesNames).toContain(name);
    expect(fixture).toMatchObject({ defaultSecret: false, disabled: false });
    expect(await catalog.securedVariablesInSecret(SECRET_FIXTURE_NAME)).toContain(name);

    // The whole observable contract of a secured variable: the name is readable and the value is
    // not, on either shape of the read. An assertion that the value round-trips cannot be written,
    // so this is the one that stands in for it.
    //
    // The status first, on both. `not.toContain` is satisfied by a 404 or a 500 body as readily as
    // by a correct listing, so without it the two strongest assertions in this case are the ones a
    // broken endpoint passes.
    const listed = await catalog.raw("get", "/v2/secured-variables");
    expect(listed.status(), "the listing that must not carry the value did not answer").toBe(200);
    const listedBody = await listed.text();
    expect(listedBody).toContain(name);
    expect(listedBody).not.toContain(value);

    const inSecret = await catalog.raw("get", `/v2/secured-variables/${SECRET_FIXTURE_NAME}`);
    expect(inSecret.status(), `the ${SECRET_FIXTURE_NAME} listing did not answer`).toBe(200);
    const inSecretBody = await inSecret.text();
    expect(inSecretBody).toContain(name);
    expect(inSecretBody).not.toContain(value);

    const updated = await catalog.updateSecuredVariables(SECRET_FIXTURE_NAME, { [name]: `${value}-again` });
    expect(updated.variablesNames).toContain(name);

    // The update rewrites what is there and does not create: a name the secret does not hold is a
    // 404, which is the only way a caller learns an update went nowhere.
    const missing = await catalog.raw("patch", "/v2/secured-variables", {
      secretName: SECRET_FIXTURE_NAME,
      variables: { [tokenized(run, "sv-absent")]: "x" },
    });
    expect(missing.status()).toBe(404);
    expect(await missing.text()).toContain("Cannot find variable");

    await catalog.deleteSecuredVariablesFromSecret(SECRET_FIXTURE_NAME, [name]);
    expect(await catalog.securedVariablesInSecret(SECRET_FIXTURE_NAME)).not.toContain(name);
  } finally {
    await catalog.deleteSecuredVariablesFromSecret(SECRET_FIXTURE_NAME, [name]).catch(() => {});
  }
});

test("the sweep reaches a secured variable inside a named secret", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  // A secured variable has no folder, so nothing but the run-token sweep removes it — and the
  // sweep reads `/v2`, because the `/v1` listing it used to read answers 410 here and reported an
  // empty stack whatever was on it. A spec failing mid-case is what leaves one behind.
  await ensureSecretFixture(catalog);
  const name = tokenized(run, "sv-residue");
  await catalog.addSecuredVariables(SECRET_FIXTURE_NAME, { [name]: "value" });

  try {
    const residue = await findResidue(catalog, run);
    const found = residue.find((each) => each.id === `${SECRET_FIXTURE_NAME}/${name}`);
    expect(found, "a secured variable carrying the run token is residue").toMatchObject({
      kind: "secured-variable",
      name,
      // The secret is what the delete takes in its path, so the row has to carry it.
      scope: SECRET_FIXTURE_NAME,
    });
  } finally {
    await catalog.deleteSecuredVariablesFromSecret(SECRET_FIXTURE_NAME, [name]).catch(() => {});
  }
});

test("a common variable carrying the run token is residue the sweep can see", { tag: ["@catalog", "@tier2"] }, async ({ catalog, run }) => {
  // The common variables are global, and the cascade reaches none of them: the per-worker folder
  // has nothing to do with a Consul key.
  const name = tokenized(run, "cv-residue");
  await catalog.addCommonVariables({ [name]: "value" });
  try {
    const residue = await findResidue(catalog, run);
    expect(residue.map((each) => `${each.kind}:${each.id}`)).toContain(`common-variable:${name}`);
  } finally {
    await catalog.deleteCommonVariables([name]);
  }
});
