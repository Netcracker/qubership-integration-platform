/** What the import and export specs share: the archive layout, the import, and the absence check. */
import { expect, type APIResponse } from "@playwright/test";
import type { Catalog, ImportResult } from "./catalog.js";

/** The one entry a chain archive holds, spelled the way the exporter spells it. */
export function chainEntry(chainId: string): string {
  return `chains/${chainId}/${chainId}.chain.cip.yaml`;
}

export async function importChains(
  catalog: Catalog,
  archive: Buffer,
): Promise<{ status: number; body: ImportResult }> {
  const response = await catalog.importChains(archive);
  return { status: response.status(), body: (await response.json()) as ImportResult };
}

/** Whether a chain answers at all, so a case can assert its absence without catching. */
export async function chainExists(catalog: Catalog, id: string): Promise<boolean> {
  return (await catalog.raw("get", `/v1/chains/${id}`)).status() === 200;
}

/** Asserts that `POST /v1/import/system` answered 200 with one `CREATED` row per service, and no other row. */
export async function expectImportedServices(
  response: APIResponse,
  services: Array<{ id: string; name?: string }>,
  message?: string,
): Promise<void> {
  expect(response.status(), await response.text()).toBe(200);
  const byId = (a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id);
  const rows = ((await response.json()) as Array<{ id: string }>).sort(byId);
  expect(rows, message).toEqual([...services].sort(byId).map((each) => expect.objectContaining({ ...each, status: "CREATED" })));
}
