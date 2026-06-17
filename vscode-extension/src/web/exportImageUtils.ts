import { Uri } from "vscode";
import { fileApi } from "./response/file";

const MAX_EXPORT_OUTPUT_NAME_LENGTH = 120;

/** Safe file base name for exported chain image files. */
export function sanitizeExportOutputName(chainId: string): string {
  const sanitized = chainId.replace(/[^\w.-]+/g, "_");
  if (sanitized.length <= MAX_EXPORT_OUTPUT_NAME_LENGTH) {
    return sanitized;
  }
  return sanitized.slice(0, MAX_EXPORT_OUTPUT_NAME_LENGTH);
}

export function resolveOutputFileUri(outputDir: string, fileName: string): Uri {
  const normalizedName = /\.(png|svg)$/i.test(fileName)
    ? fileName
    : `${fileName}.png`;
  const hasScheme = /^[a-zA-Z][\w+.-]*:/.test(outputDir);
  if (hasScheme) {
    return Uri.joinPath(Uri.parse(outputDir), normalizedName);
  }
  if (outputDir.startsWith("/")) {
    return Uri.joinPath(Uri.file(outputDir), normalizedName);
  }
  return Uri.joinPath(fileApi.getRootDirectory(), outputDir, normalizedName);
}

export function decodeBase64(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) {
    bytes[i] = binary.codePointAt(i) ?? 0;
  }
  return bytes;
}
