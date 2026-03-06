export function getFileExtension(fileName: string): string | undefined {
  const idx = fileName.lastIndexOf(".");

  return idx ? fileName.substring(idx + 1, fileName.length) : undefined;
}

export function removeExtension(fileName: string): string {
  const idx = fileName?.lastIndexOf(".");
  if (idx) {
    fileName = fileName.substring(0, idx);
  }
  return fileName;
}
