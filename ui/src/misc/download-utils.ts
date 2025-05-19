export function downloadFile(file: File) {
  const link = document.createElement("a");
  link.href = URL.createObjectURL(file);
  link.download = file.name;
  link.target = "_blank";
  link.click();
  link.remove();
}