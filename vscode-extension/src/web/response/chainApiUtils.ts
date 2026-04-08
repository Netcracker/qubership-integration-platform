import vscode from "vscode";
import { getCurrentChainId } from "./chainApiRead";
import { Element, LibraryElement } from "@netcracker/qip-ui";
import { Element as ElementSchema } from "@netcracker/qip-schemas";

export async function getChainUri(mainFolderUri: vscode.Uri): Promise<string> {
  const result = `/chains/${await getCurrentChainId(mainFolderUri)}/graph`;
  console.log("getChainUri", result);
  return result;
}

export function findElementByIdOrError(
  elements: ElementSchema[] | undefined,
  elementId: string,
  parentId: string | undefined = undefined,
) {
  const element = findElementById(elements, elementId, parentId);
  if (!element) {
    const message = `Element with id=${elementId} and parentId=${parentId} not found`;
    console.error(message);
    throw new Error(message);
  }
  return element;
}

export function findElementById(
  elements: ElementSchema[] | undefined,
  elementId: string,
  parentId: string | undefined = undefined,
):
  | {
      element: ElementSchema;
      parentId: string | undefined;
    }
  | undefined {
  if (!elements) {
    return undefined;
  }

  for (const element of elements) {
    if (element.id === elementId) {
      return { element, parentId };
    }

    const found = findElementById(
      element.children as ElementSchema[],
      elementId,
      element.id,
    );
    if (found) {
      return found;
    }
  }

  return undefined;
}

export function getElementChildren(
  children: ElementSchema[] | undefined,
): ElementSchema[] {
  const result: ElementSchema[] = [];
  if (children?.length) {
    for (const child of children) {
      if ((child.children as ElementSchema[])?.length) {
        result.push(...getElementChildren(child.children as ElementSchema[]));
      }
      result.push(child);
    }
  }

  return result;
}

export function getParsedElementChildren(
  children: Element[] | undefined,
): Element[] {
  const result: Element[] = [];
  if (children?.length) {
    for (const child of children) {
      if (child.children?.length) {
        result.push(...getParsedElementChildren(child.children));
      }
      result.push(child);
    }
  }

  return result;
}

export function replaceElementPlaceholders(
  properties: any,
  chainId: string,
  elementId: string,
) {
  for (let property in properties) {
    if (typeof properties[property] === "string") {
      properties[property] = replacePlaceholders(
        properties[property],
        chainId,
        elementId,
      );
    }
  }
}

export function replacePlaceholders(
  value: string,
  chainId: string,
  elementId: string,
): string {
  return value
    .replace(ChainElementPlaceholders.CHAIN_ID_PLACEHOLDER, chainId)
    .replace(
      ChainElementPlaceholders.CREATED_ELEMENT_ID_PLACEHOLDER,
      elementId,
    );
}

export function cloneElementSchema(source: ElementSchema) {
  const elementClone = structuredClone(source);
  elementClone.id = crypto.randomUUID();

  return elementClone;
}

export function resetPropertiesToDefault(
  chainId: string,
  element: ElementSchema,
  libraryElement: LibraryElement,
) {
  const libraryProperties = [
    ...(libraryElement.properties.common ?? []),
    ...(libraryElement.properties.advanced ?? []),
    ...(libraryElement.properties.hidden ?? []),
    ...(libraryElement.properties.unknown ?? []),
  ];

  for (const libraryProperty of libraryProperties) {
    if (libraryProperty.resetValueOnCopy) {
      (element.properties as any)[libraryProperty.name] = replacePlaceholders(
        (libraryProperty.default as string) ?? "",
        chainId,
        element.id,
      );
    }
  }
}

//TODO WA for exporting only types in UI
export enum ChainCommitRequestAction {
  NONE = "NONE",
  SNAPSHOT = "SNAPSHOT",
  DEPLOY = "DEPLOY",
}

export enum LibraryElementQuantity {
  ONE = "one",
  ONE_OR_ZERO = "one-or-zero",
  ONE_OR_MANY = "one-or-many",
}

export enum LibraryInputQuantity {
  ONE = "one",
  ANY = "any",
}

export enum ChainElementPlaceholders {
  CREATED_ELEMENT_ID_PLACEHOLDER = "%%{created-element-id-placeholder}",
  CHAIN_ID_PLACEHOLDER = "%%{chain-id-placeholder}",
}
