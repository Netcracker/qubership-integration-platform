import JSZip from "jszip";
import { AxiosResponse } from "axios";

export function downloadFile(file: File) {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(file);
  link.download = file.name;
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

export function getFileFromResponse(response: AxiosResponse<Blob>): File {
  const contentDisposition = response.headers?.["content-disposition"] as string;
  const fileName = contentDisposition
    ?.replace("attachment; filename=", "")
    .replace(/^"|"$/g, "");
  return new File([response.data], fileName, {
    type: response.data.type.toString(),
  });
}
