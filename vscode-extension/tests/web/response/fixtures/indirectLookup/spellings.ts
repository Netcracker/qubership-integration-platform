// Fixtures, not production code. Each function here swallows a lookup — or a parse inside a loop —
// while handing the callee to something else to invoke, which is how a call resolves to
// `Function.call` or `Reflect.apply` instead of to the function it reaches. The guard analyzes this
// folder as a second root and has to name every one of them.

import { Uri } from "vscode";
import { fileApi } from "../../../../../src/web/response/file/fileApiProvider";
import { findServiceFileById } from "../../../../../src/web/response/file/serviceFileLookup";

export async function viaCall(serviceId: string): Promise<Uri | undefined> {
  try {
    return await findServiceFileById.call(undefined, serviceId);
  } catch {
    return undefined;
  }
}

export async function viaApply(serviceId: string): Promise<Uri | undefined> {
  try {
    return await findServiceFileById.apply(undefined, [serviceId]);
  } catch {
    return undefined;
  }
}

export async function viaBoundImmediately(
  serviceId: string,
): Promise<Uri | undefined> {
  try {
    return await findServiceFileById.bind(undefined)(serviceId);
  } catch {
    return undefined;
  }
}

export async function viaBoundVariable(
  serviceId: string,
): Promise<Uri | undefined> {
  const bound = findServiceFileById.bind(undefined);
  try {
    return await bound(serviceId);
  } catch {
    return undefined;
  }
}

export async function viaReflectApply(
  serviceId: string,
): Promise<Uri | undefined> {
  try {
    return await Reflect.apply(findServiceFileById, undefined, [serviceId]);
  } catch {
    return undefined;
  }
}

export async function parseLoopViaCall(fileUris: Uri[]): Promise<unknown[]> {
  const parsed: unknown[] = [];
  for (const fileUri of fileUris) {
    try {
      parsed.push(await fileApi.parseFile.call(fileApi, fileUri));
    } catch {
      continue;
    }
  }
  return parsed;
}
