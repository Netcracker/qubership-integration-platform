import JSZip from "jszip";
import { AxiosResponse } from "axios";

export function downloadFile(file: File, defaultName?: string) {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(file);
  link.download = file.name || defaultName || "download";
  link.target = "_blank";
  link.click();
  link.remove();
}

export async function mergeZipArchives(blobs: Blob[]) {
  const zip = new JSZip();

  for (const blob of blobs) {
    await zip
      .folder("")
      ?.loadAsync(new Blob([blob], { type: "application/zip" }));
  }

  return zip.generateAsync({ type: "blob" });
}

/**
 * `defaultName` covers a response without a `Content-Disposition` header. Without
 * it the name falls through to the string "undefined", which `downloadFile`
 * cannot tell from a real one.
 */
export function getFileFromResponse(
  response: AxiosResponse<Blob>,
  defaultName = "download",
): File {
  const contentDisposition = response.headers?.[
    "content-disposition"
  ] as string;
  const fileName = contentDisposition
    ?.replace("attachment; filename=", "")
    .replace(/^"|"$/g, "");
  return new File([response.data], fileName || defaultName, {
    type: response.data.type.toString(),
  });
}
