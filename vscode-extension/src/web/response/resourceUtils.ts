import { Uri } from "vscode";
import { Element as ElementSchema } from "@netcracker/qip-schemas";
import { fileApi } from "./file";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function toResourcePath(filename: string): string {
  return `resources/${filename}`;
}

function collectFilenamesFromServiceCallElement(
  element: ElementSchema,
  out: Set<string>,
): void {
  if ((element.type as unknown as string) !== "service-call") {
    return;
  }
  const props = element.properties as unknown;
  if (!isRecord(props)) {
    return;
  }
  const before = props["before"];
  if (isRecord(before) && typeof before["propertiesFilename"] === "string" && before["propertiesFilename"]) {
    out.add(before["propertiesFilename"] as string);
  }
  const after = props["after"];
  if (Array.isArray(after)) {
    for (const block of after) {
      if (isRecord(block) && typeof block["propertiesFilename"] === "string" && block["propertiesFilename"]) {
        out.add(block["propertiesFilename"] as string);
      }
    }
  }
}

export function collectFilenamesFromElementTree(
  elements: ElementSchema[] | undefined,
  out: Set<string>,
): void {
  if (!elements?.length) {
    return;
  }
  const stack: ElementSchema[] = [...elements];
  while (stack.length) {
    const el = stack.pop() as ElementSchema;
    collectFilenamesFromServiceCallElement(el, out);
    const children = el.children as ElementSchema[] | undefined;
    if (children?.length) {
      for (const child of children) {
        stack.push(child);
      }
    }
  }
}

export async function deleteElementsPropertyFiles(
  fileUri: Uri,
  removedElements: ElementSchema[],
): Promise<void> {
  async function handleServiceCallProperty(beforeAfterBlock: Record<string, unknown>): Promise<void> {
    const type = beforeAfterBlock["type"];
    const filename = beforeAfterBlock["propertiesFilename"];
    if (typeof filename !== "string" || !filename) {
      return;
    }
    if (type === "script") {
      (beforeAfterBlock as Record<string, unknown>)["script"] = await fileApi.removeFile(fileUri, toResourcePath(filename));
    } else if (typeof type === "string" && type.startsWith("mapper")) {
      await fileApi.removeFile(fileUri, toResourcePath(filename));
    }
  }

  for (const element of removedElements) {
    const props = element.properties as unknown;
    if (isRecord(props) && typeof props["propertiesToExportInSeparateFile"] === "string" && props["propertiesToExportInSeparateFile"] && typeof props["propertiesFilename"] === "string" && props["propertiesFilename"]) {
      await fileApi.removeFile(fileUri, toResourcePath(props["propertiesFilename"] as string));
    }

    if ((element.type as unknown as string) === "service-call" && isRecord(props)) {
      const after = props["after"];
      if (Array.isArray(after)) {
        for (const afterBlock of after) {
          if (isRecord(afterBlock)) {
            await handleServiceCallProperty(afterBlock);
          }
        }
      }
      const before = props["before"];
      if (isRecord(before)) {
        await handleServiceCallProperty(before);
      }
    }

    const children = element.children as ElementSchema[] | undefined;
    if (children?.length) {
      await deleteElementsPropertyFiles(fileUri, children);
    }
  }
}

export async function cleanupOrphanPropertyFiles(
  fileUri: Uri,
  oldFilenames: Set<string>,
  newFilenames: Set<string>,
  chainElements: ElementSchema[],
): Promise<void> {
  const candidates: string[] = [];
  for (const filename of oldFilenames) {
    if (!newFilenames.has(filename)) {
      candidates.push(filename);
    }
  }
  if (!candidates.length) {
    return;
  }
  const liveFilenames = new Set<string>();
  collectFilenamesFromElementTree(chainElements, liveFilenames);
  const orphans: string[] = candidates.filter((f) => !liveFilenames.has(f));
  if (!orphans.length) {
    return;
  }
  for (const filename of new Set(orphans)) {
    await fileApi.removeFile(fileUri, toResourcePath(filename));
  }
}
