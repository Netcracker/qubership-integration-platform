import { Uri } from "vscode";
import type { ExportImagesStartupPayload, ExportImagesTarget } from "@netcracker/qip-ui";
import type { ExportImagesCommandConfig } from "./exportImageTypes";
import { sanitizeExportOutputName } from "./exportImageUtils";
import { fileApi } from "./response/file";

type UriApi = {
  parse: (value: string) => Uri;
  file: (path: string) => Uri;
};

export function toUri(
  value: string | Uri | undefined,
  uriApi: UriApi = Uri,
): Uri | undefined {
  if (!value) {
    return undefined;
  }
  if (typeof value !== "string") {
    return value;
  }
  if (/^[a-zA-Z][\w+.-]*:/.test(value)) {
    return uriApi.parse(value);
  }
  return uriApi.file(value);
}

export async function getExportTargetFromFilePath(
  filePath: string | Uri,
): Promise<ExportImagesTarget> {
  const uri = toUri(filePath);
  if (!uri) {
    throw new Error("Export file path is required");
  }

  const chain = await fileApi.parseFile(uri);
  const chainId = typeof chain?.id === "string" ? chain.id : undefined;
  if (!chainId) {
    throw new Error(`Unable to determine chain id from ${uri.toString()}`);
  }

  return {
    chainId,
    filePath: uri.toString(),
    outputName: sanitizeExportOutputName(chainId),
  };
}

export function resolveExportPaths(
  filePath: string | Uri | undefined,
  config?: ExportImagesCommandConfig,
): string[] {
  if (config?.filePaths?.length) {
    return config.filePaths;
  }
  if (filePath) {
    const uri = toUri(filePath);
    if (!uri) {
      return [];
    }
    return [uri.toString()];
  }
  return [];
}

export function buildExportImagesStartupPayload(
  filePath: string | Uri | undefined,
  config: ExportImagesCommandConfig,
  targets: ExportImagesTarget[],
): ExportImagesStartupPayload {
  return {
    filePath: typeof filePath === "string" ? filePath : filePath?.toString(),
    exportConfig: {
      outputDir: config.outputDir,
      imageFormat: "png",
      targets,
    },
    targets,
  };
}
