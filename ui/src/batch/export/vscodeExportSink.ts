import { api } from "../../api/api.ts";
import { VSCodeExtensionApi } from "../../api/rest/vscodeExtensionApi.ts";
import type { BatchExportItemResult } from "./types.ts";

export async function saveExportedImageToVsCode(
  outputDir: string,
  result: BatchExportItemResult,
): Promise<void> {
  if (!(api instanceof VSCodeExtensionApi)) {
    throw new Error("VS Code export sink is available only in VS Code webview");
  }

  await api.sendMessageToExtension("saveExportedImage", {
    outputDir,
    fileName: result.fileName,
    contentBase64: result.contentBase64,
  });
}

export async function reportExportImagesProgress(
  type: string,
  payload?: unknown,
): Promise<void> {
  if (api instanceof VSCodeExtensionApi) {
    await api.sendMessageToExtension(type, payload);
  }
}
