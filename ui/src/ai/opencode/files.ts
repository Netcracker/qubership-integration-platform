import type { FilePartInput } from "@opencode-ai/sdk/v2/client";

export async function prepareFiles(files: File[]): Promise<FilePartInput[]> {
  if (files.length > 5) throw new Error("Attach up to five files.");
  return Promise.all(
    files.map((file) => {
      if (file.size > 10 * 1024 * 1024)
        throw new Error(`${file.name} exceeds the 10 MB attachment limit.`);
      const mime =
        /\.(txt|md|json|yaml|yml|csv)$/i.test(file.name) ||
        file.type.startsWith("text/")
          ? "text/plain"
          : file.type;
      if (
        ![
          "text/plain",
          "application/pdf",
          "image/png",
          "image/jpeg",
          "image/gif",
          "image/webp",
        ].includes(mime)
      ) {
        throw new Error(
          `${file.name} is not a supported attachment. Use text, PDF, PNG, JPEG, GIF, or WebP.`,
        );
      }
      return new Promise<FilePartInput>((resolve, reject) => {
        const reader = new FileReader();
        reader.onerror = () =>
          reject(new Error(`Cannot read ${file.name}. Select the file again.`));
        reader.onabort = () =>
          reject(new Error(`Reading ${file.name} was canceled.`));
        reader.onload = () => {
          if (typeof reader.result !== "string") {
            reject(
              new Error(`Cannot read ${file.name}. Select the file again.`),
            );
            return;
          }
          const data = reader.result;
          resolve({
            type: "file",
            filename: file.name,
            mime,
            url: `data:${mime};base64,${data.slice(data.indexOf(",") + 1)}`,
          });
        };
        reader.readAsDataURL(file);
      });
    }),
  );
}
