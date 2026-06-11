import vscode, { Uri } from "vscode";

type ImageFormat = "png" | "svg";

type ImageSaveTarget = {
  uri: string;
  format: ImageFormat;
};

/**
 * Shows a native save dialog for the chain graph image and returns the chosen
 * target. The format is derived from the extension the user picks (PNG raster
 * or true-vector SVG). Returns null when the user cancels.
 */
export async function chooseImageSavePath(
  baseUri: Uri,
  suggestedName: string,
): Promise<ImageSaveTarget | null> {
  const directory = await resolveDirectoryUri(baseUri);
  const safeName = (suggestedName || "chain").replace(/[\\/:*?"<>|]+/g, "_");
  const defaultUri = Uri.joinPath(directory, `${safeName}.png`);

  const target = await vscode.window.showSaveDialog({
    defaultUri,
    title: "Export chain graph as image",
    filters: {
      "PNG image": ["png"],
      "SVG image": ["svg"],
    },
  });

  if (!target) {
    return null;
  }

  const format: ImageFormat = target.path.toLowerCase().endsWith(".svg")
    ? "svg"
    : "png";

  return { uri: target.toString(), format };
}

/** Writes the PNG bytes received from the webview to the chosen uri. */
export async function writeImageFile(
  uriString: string,
  data: ArrayBuffer | Uint8Array,
): Promise<void> {
  const uri = Uri.parse(uriString);
  const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);

  await vscode.workspace.fs.writeFile(uri, bytes);

  vscode.window.showInformationMessage(
    `Chain graph image saved: ${vscode.workspace.asRelativePath(uri)}`,
  );
}

async function resolveDirectoryUri(uri: Uri): Promise<Uri> {
  try {
    const stat = await vscode.workspace.fs.stat(uri);
    if (stat.type === vscode.FileType.File) {
      return Uri.joinPath(uri, "..");
    }
    return uri;
  } catch {
    return Uri.joinPath(uri, "..");
  }
}
