import type { Api } from "../api/api.ts";
import type { ExportChainOptions } from "../components/modal/ExportChains.tsx";
import { downloadFile, mergeZipArchives } from "./download-utils.ts";
import { exportAdditionsForChains } from "./export-additions.ts";

/** Downloads chains as one archive, with the options the chains page export offers. */
export async function downloadChainsExport(
  api: Api,
  chainIds: string[],
  options: ExportChainOptions,
): Promise<void> {
  const chainsFile = await api.exportChains(chainIds, options.exportSubchains);
  const additions = await exportAdditionsForChains({
    api,
    chainIdsForUsedSystems: chainIds,
    options: {
      exportServices: options.exportServices,
      exportVariables: options.exportVariables,
    },
  });
  const archive = await mergeZipArchives(
    [chainsFile, ...additions].filter((file) => file.size !== 0),
  );
  downloadFile(
    new File([archive], chainsFile.name, { type: "application/zip" }),
  );
}
