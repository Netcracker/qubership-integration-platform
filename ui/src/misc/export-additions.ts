import type { Api } from "../api/api.ts";

export type ExportAdditionsOptions = {
  exportServices: boolean;
  exportVariables: boolean;
};

export async function exportAdditionsForChains(params: {
  api: Api;
  chainIdsForUsedSystems: string[];
  options: ExportAdditionsOptions;
}): Promise<File[]> {
  const { api, chainIdsForUsedSystems, options } = params;
  const data: File[] = [];

  if (options.exportServices) {
    const usedServices = await api.getServicesUsedByChains(
      chainIdsForUsedSystems,
    );
    if (usedServices.length > 0) {
      const serviceIds = usedServices.map((i) => i.systemId);
      const modelIds = usedServices.flatMap((i) => i.usedSystemModelIds ?? []);

      const servicesData = await api.exportServices(serviceIds, modelIds);
      data.push(servicesData);

      try {
        const contextServicesData = await api.exportContextServices(serviceIds);
        data.push(contextServicesData);
      } catch (e: unknown) {
        // VSCodeExtensionApi может не поддерживать этот метод — не ломаем экспорт.
        const msg = e instanceof Error ? e.message : String(e);
        if (!msg.toLowerCase().includes("not implemented")) {
          throw e;
        }
      }
    }
  }

  if (options.exportVariables) {
    const variablesData = await api.exportVariables([], true);
    data.push(variablesData);
  }

  return data;
}
