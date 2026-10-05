/** What the chain import and export specs share: the archive layout, the import, and the absence check. */
import type { Catalog, ImportResult } from "./catalog.js";

/** The one entry a chain archive holds, spelled the way the exporter spells it. */
export function chainEntry(chainId: string): string {
  return `chains/${chainId}/${chainId}.chain.qip.yaml`;
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
