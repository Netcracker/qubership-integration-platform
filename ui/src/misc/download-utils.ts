import JSZip from "jszip";

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
