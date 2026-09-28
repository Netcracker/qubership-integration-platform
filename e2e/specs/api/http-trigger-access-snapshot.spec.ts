/**
 * The access control axes of `http-trigger`, asserted where they are observable: the compiled
 * snapshot.
 *
 * Not at runtime, because nothing there differs. The engine's `SecurityConfiguration` is
 * `anyRequest().permitAll()`, so NONE, RBAC and ABAC give an identical response and an identical
 * trace on this stack, and a runtime spec would pass for any value of the axis. That half is
 * recorded as a gap in `registry/elements.ts`.
 *
 * The compile is read off `xmlDefinition` from `GET /v1/catalog/chains/{c}/snapshots/{id}`, not off
 * `GET /v2/catalog/snapshots/{id}/full`. Measured: `/full` echoes back the `accessControlType` the
 * case just wrote, so it holds for any value, while the XML omits the property and carries what
 * the template made of it — the policy the validation step is wrapped in, and the ABAC resource
 * properties set before it.
 */
import { test, expect } from "../../support/fixtures.js";
import { covers } from "../../registry/covers.js";
import { tokenizedChain } from "../../support/deployable.js";
import type { Catalog } from "../../support/catalog.js";

/** Compiles a chain whose trigger carries `properties` and answers the route XML. */
async function compiledWith(
  catalog: Catalog,
  run: string,
  folderId: string,
  what: string,
  properties: Record<string, unknown>,
): Promise<string> {
  const chain = await tokenizedChain(catalog, run, { prefix: "access", what, parentId: folderId });
  await catalog.patchElementProperties(chain.id, chain.triggerId, properties);
  const built = await catalog.createSnapshot(chain.id);
  return (await catalog.getSnapshot(chain.id, built.id)).xmlDefinition ?? "";
}

function policyRefs(xml: string): string[] {
  return [...xml.matchAll(/<policy\b[^>]*\bref="([^"]+)"/g)].map((match) => match[1]);
}

test("accessControlType NONE compiles the validation step with no policy around it", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  covers("http-trigger", "accessControlType", "NONE");
  const xml = await compiledWith(catalog, run, folder.id, "none", { accessControlType: "NONE" });

  expect(xml, "the snapshot compiled no trigger").toContain("httpTriggerProcessor");
  expect(policyRefs(xml)).toEqual([]);
  expect(xml).not.toContain("internalProperty_rbac_access_policy");
  expect(xml).not.toContain("abacParameters_operation");
});

test("accessControlType RBAC wraps the validation step in rbacPolicy with the roles inlined", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  covers("http-trigger", "accessControlType", "RBAC");
  const xml = await compiledWith(catalog, run, folder.id, "rbac", {
    accessControlType: "RBAC",
    roles: ["e2e-access-role"],
  });

  expect(policyRefs(xml)).toEqual(["rbacPolicy"]);
  expect(xml).toContain("internalProperty_rbac_access_policy");
  expect(xml).toContain("e2e-access-role");
  expect(xml).not.toContain("abacParameters_operation");
});

test("accessControlType ABAC wraps the validation step in abacPolicy with a String resource", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  // String is the default `resourceDataType`, so the ABAC case is its case too.
  covers("http-trigger", "accessControlType", "ABAC");
  covers("http-trigger", "abacParameters/resourceDataType", "String");
  const xml = await compiledWith(catalog, run, folder.id, "abac-string", {
    accessControlType: "ABAC",
    abacParameters: {
      resourceType: "CHAIN",
      operation: "ALL",
      resourceDataType: "String",
      resourceString: "e2e-access-resource",
    },
  });

  expect(policyRefs(xml)).toEqual(["abacPolicy"]);
  expect(xml).toContain("abacParameters_resourceString");
  expect(xml).toContain("e2e-access-resource");
  expect(xml).not.toContain("abacParameters_resourceMap_");
  expect(xml).not.toContain("internalProperty_rbac_access_policy");
});

test("resourceDataType Map compiles one ABAC property per map entry and no String resource", { tag: ["@catalog", "@tier2"] }, async ({ catalog, folder, run }) => {
  covers("http-trigger", "abacParameters/resourceDataType", "Map");
  const xml = await compiledWith(catalog, run, folder.id, "abac-map", {
    accessControlType: "ABAC",
    abacParameters: {
      resourceType: "CHAIN",
      operation: "ALL",
      resourceDataType: "Map",
      resourceMap: { tenant: "e2e-access-tenant" },
    },
  });

  expect(policyRefs(xml)).toEqual(["abacPolicy"]);
  expect(xml).toContain("abacParameters_resourceMap_tenant");
  expect(xml).toContain("e2e-access-tenant");
  expect(xml).not.toContain("abacParameters_resourceString");
});
