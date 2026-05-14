import {
  Element as ElementSchema,
  Chain as ChainSchema,
} from "@netcracker/qip-schemas";
import {
  ActionDifference,
  CreateElementRequest,
  TransferElementRequest,
} from "@netcracker/qip-ui";
import {
  findAndRemoveElementById,
  getDefaultElement,
  getDefaultElementByType,
} from "./chainApiModify";
import { findElementById, getElementChildren } from "./chainApiUtils";
import { Uri } from "vscode";
import { parseElement, parseElements } from "./chainApiRead";
import { fileApi } from "./file";

export const SWIMLANE_TYPE_NAME = "swimlane";
const DEFAULT_SWIMLANE_NAME = "Default swimlane";
const REUSE_SWIMLANE_NAME = "Reuse swimlane";

export function isSwimlane(element: ElementSchema) {
  return (element.type as unknown as string) === SWIMLANE_TYPE_NAME;
}

export function isDefaultSwimlane(element: ElementSchema, chain: ChainSchema) {
  return isSwimlane(element) && element.id === chain.content.defaultSwimlaneId;
}

export function isReuseSwimlane(element: ElementSchema, chain: ChainSchema) {
  return isSwimlane(element) && element.id === chain.content.reuseSwimlaneId;
}

export function swimlaneValidations(
  chain: ChainSchema,
  element: ElementSchema,
  elementRequest: CreateElementRequest,
) {
  if (
    !isReuseElement(element) &&
    isTransferToTheRootOfReuseSwimlane(
      chain,
      elementRequest.parentElementId,
      elementRequest.swimlaneId,
    )
  ) {
    throw new Error(`Only Reuse element can be added to Reuse Swimlane`);
  }
}

export function transferToSwimlaneValidations(
  chain: ChainSchema,
  element: ElementSchema,
  elementRequest: TransferElementRequest,
) {
  if (
    !isReuseElement(element) &&
    isTransferToTheRootOfReuseSwimlane(
      chain,
      elementRequest.parentId ?? undefined,
      elementRequest.swimlaneId ?? undefined,
    )
  ) {
    throw new Error(`Element ${element.id} cannot be moved to Reuse group`);
  }
}

function isReuseElement(element: ElementSchema) {
  return (element.type as unknown as string) === "reuse";
}

function isTransferToTheRootOfReuseSwimlane(
  chain: ChainSchema,
  parentId?: string,
  swimlaneId?: string,
): boolean {
  return (
    swimlaneId !== undefined &&
    swimlaneId === chain.content.reuseSwimlaneId &&
    !isParentElementReuse(chain, parentId)
  );
}

function isParentElementReuse(chain: ChainSchema, parentId?: string): boolean {
  if (!parentId) {
    return false;
  }

  const chainElements = chain.content.elements as ElementSchema[];
  return (
    (findElementById(chainElements, parentId)?.element
      ?.type as unknown as string) === "reuse"
  );
}

export function isTransferOutOfSwimlane(
  elementRequest: TransferElementRequest,
  element: ElementSchema,
  chain: ChainSchema,
): boolean {
  if (elementRequest.parentId) {
    const chainElements = chain.content.elements as ElementSchema[];
    const newParent = findElementById(
      chainElements,
      elementRequest.parentId,
    )?.element;
    if (newParent?.swimlaneId) {
      return false;
    }
  }
  return element.swimlaneId !== undefined && !elementRequest.swimlaneId;
}

export async function createSwimlane(
  mainFolderUri: Uri,
  chain: ChainSchema,
  elementRequest: CreateElementRequest,
): Promise<ActionDifference> {
  const chainDiff: ActionDifference = {
    createdElements: [],
    updatedElements: [],
  };
  const chainElements = chain.content.elements as ElementSchema[];

  if (chain.content.defaultSwimlaneId) {
    const swimlane: ElementSchema = await getDefaultElementByType(
      chain.id,
      elementRequest,
    );
    chainElements.push(swimlane);
    chainDiff.createdElements?.push(
      await parseElement(mainFolderUri, swimlane, chain.id),
    );
  } else {
    const defaultSwimlane: ElementSchema = await getDefaultElementByType(
      chain.id,
      elementRequest,
    );
    defaultSwimlane.name = DEFAULT_SWIMLANE_NAME;
    chainElements.push(defaultSwimlane);
    chain.content.defaultSwimlaneId = defaultSwimlane.id;
    chainDiff.createdDefaultSwimlaneId = defaultSwimlane.id;

    const updatedElements = await updateSwimlaneForElements(
      defaultSwimlane.id,
      chainElements,
      (element: ElementSchema) =>
        (element.type as unknown as string) !== "reuse",
    );

    chainDiff.createdElements?.push(
      await parseElement(mainFolderUri, defaultSwimlane, chain.id),
    );
    if (updatedElements.length) {
      chainDiff.updatedElements?.push(
        ...(await parseElements(mainFolderUri, updatedElements, chain.id)),
      );
    }

    const chainHasReuseElements = chainElements.some((element) =>
      isReuseElement(element),
    );
    if (chainHasReuseElements) {
      await createReuseSwimlaneAndUpdateElements(
        mainFolderUri,
        chain,
        chainDiff,
      );
    }
  }
  await fileApi.writeMainChain(mainFolderUri, chain);

  return chainDiff;
}

export async function enrichElementWithSwimlaneId(
  fileUri: Uri,
  chain: ChainSchema,
  elementRequest: CreateElementRequest,
  newElement: ElementSchema,
  chainDiff: ActionDifference,
): Promise<void> {
  const chainElements = chain.content.elements as ElementSchema[];

  let swimlaneId = chain.content.defaultSwimlaneId as string;
  if (swimlaneId) {
    if (isReuseElement(newElement)) {
      swimlaneId =
        chain.content.reuseSwimlaneId === undefined
          ? (await createReuseSwimlane(fileUri, chain, chainDiff)).id
          : (chain.content.reuseSwimlaneId as string);
    } else if (elementRequest.swimlaneId) {
      swimlaneId = findSwimlaneByIdOrError(
        chainElements,
        elementRequest.swimlaneId,
        chain.id,
      ).id;
    }
  }
  if (swimlaneId) {
    newElement.swimlaneId = swimlaneId;
  }
}

export function findSwimlaneByIdOrError(
  elements: ElementSchema[],
  swimlaneId: string,
  chainId: string,
): ElementSchema {
  const swimlane = elements.find(
    (element) => isSwimlane(element) && element.id === swimlaneId,
  );
  if (!swimlane) {
    throw new Error(
      `Swimlane ${swimlaneId} does not exist in chain ${chainId}`,
    );
  }

  return swimlane;
}

async function createReuseSwimlane(
  fileUri: Uri,
  chain: ChainSchema,
  chainDiff: ActionDifference,
): Promise<ElementSchema> {
  const chainElements = chain.content.elements as ElementSchema[];

  const reuseSwimlane: ElementSchema = await getDefaultElement(
    chain.id,
    SWIMLANE_TYPE_NAME,
  );
  reuseSwimlane.name = REUSE_SWIMLANE_NAME;
  (reuseSwimlane as any).properties.color = "Green";
  chainElements.push(reuseSwimlane);
  chain.content.reuseSwimlaneId = reuseSwimlane.id;
  chainDiff.createdReuseSwimlaneId = reuseSwimlane.id;

  chainDiff.createdElements?.push(
    await parseElement(fileUri, reuseSwimlane, chain.id),
  );

  return reuseSwimlane;
}

async function createReuseSwimlaneAndUpdateElements(
  fileUri: Uri,
  chain: ChainSchema,
  chainDiff: ActionDifference,
) {
  const reuseSwimlane: ElementSchema = await createReuseSwimlane(
    fileUri,
    chain,
    chainDiff,
  );

  const updatedReuseElements = await updateSwimlaneForElements(
    reuseSwimlane.id,
    chain.content.elements as ElementSchema[],
    (element: ElementSchema) => (element.type as unknown as string) === "reuse",
  );

  chainDiff.updatedElements?.push(
    ...(await parseElements(fileUri, updatedReuseElements, chain.id)),
  );
}

export async function deleteSwimlane(
  fileUri: Uri,
  swimlaneElement: ElementSchema,
  chain: ChainSchema,
): Promise<ActionDifference> {
  const chainDiff: ActionDifference = {
    removedElements: [],
    updatedElements: [],
  };

  if (isDefaultSwimlane(swimlaneElement, chain)) {
    await deleteDefaultSwimlane(fileUri, chain, chainDiff);
  } else if (isReuseSwimlane(swimlaneElement, chain)) {
    const elementsInReuseSwimlane = (
      chain.content.elements as ElementSchema[]
    ).filter(
      (chainElement) => chainElement.swimlaneId === swimlaneElement.id,
    ).length;

    if (elementsInReuseSwimlane === 0) {
      deleteOnlySwimlane(fileUri, chain, swimlaneElement.id, chainDiff);
    } else {
      await deleteDefaultSwimlane(fileUri, chain, chainDiff);
    }
  } else if (chain.content.defaultSwimlaneId) {
    const updatedElements = await updateSwimlaneForElements(
      chain.content.defaultSwimlaneId as string,
      chain.content.elements as ElementSchema[],
      (element) => element.swimlaneId === swimlaneElement.id,
    );
    chainDiff.updatedElements?.push(
      ...(await parseElements(fileUri, updatedElements, chain.id)),
    );
    deleteOnlySwimlane(fileUri, chain, swimlaneElement.id, chainDiff);
  } else {
    deleteSwimlaneWithElements(fileUri, chain, swimlaneElement.id, chainDiff);
  }

  return chainDiff;
}

async function deleteDefaultSwimlane(
  fileUri: Uri,
  chain: ChainSchema,
  chainDiff: ActionDifference,
) {
  const chainElements = chain.content.elements as ElementSchema[];

  const commonSwimlanesCount = chainElements
    .filter((element) => isSwimlane(element))
    .filter(
      (element) =>
        !isDefaultSwimlane(element, chain) && !isReuseSwimlane(element, chain),
    ).length;

  if (commonSwimlanesCount > 0) {
    throw new Error(
      "Default and Reuse swimlanes cannot be removed if the chain contains other swimlanes",
    );
  }

  await deleteSwimlaneWithElements(
    fileUri,
    chain,
    chain.content.defaultSwimlaneId as string,
    chainDiff,
  );

  if (chain.content.reuseSwimlaneId) {
    await deleteSwimlaneWithElements(
      fileUri,
      chain,
      chain.content.reuseSwimlaneId as string,
      chainDiff,
    );
  }
}

async function deleteSwimlaneWithElements(
  fileUri: Uri,
  chain: ChainSchema,
  swimlaneId: string,
  chainDiff: ActionDifference,
) {
  await deleteOnlySwimlane(fileUri, chain, swimlaneId, chainDiff);

  const updatedElements = await updateSwimlaneForElements(
    undefined,
    chain.content.elements as ElementSchema[],
    (element) => element.swimlaneId === swimlaneId,
  );
  if (updatedElements.length) {
    chainDiff.updatedElements?.push(
      ...(await parseElements(fileUri, updatedElements, chain.id)),
    );
  }
}

async function deleteOnlySwimlane(
  fileUri: Uri,
  chain: ChainSchema,
  swimlaneId: string,
  chainDiff: ActionDifference,
) {
  const chainElements = chain.content.elements as ElementSchema[];

  const deletedSwimlane = findAndRemoveElementById(chainElements, swimlaneId)!;

  if (swimlaneId === chain.content.defaultSwimlaneId) {
    chain.content.defaultSwimlaneId = undefined;
  } else if (swimlaneId === chain.content.reuseSwimlaneId) {
    chain.content.reuseSwimlaneId = undefined;
  }

  chainDiff.removedElements?.push(
    await parseElement(fileUri, deletedSwimlane, chain.id),
  );
}

async function updateSwimlaneForElements(
  swimlaneId: string | undefined,
  chainElements: ElementSchema[],
  predicate: (element: ElementSchema) => boolean,
): Promise<ElementSchema[]> {
  const nonSwimlaneElements = chainElements.filter(
    (element) => !isSwimlane(element),
  );
  const updatedElements: ElementSchema[] = [];
  for (const element of nonSwimlaneElements) {
    if (predicate(element)) {
      updateSwimlaneId(element, swimlaneId);
      updatedElements.push(element);
    }
  }
  return updatedElements;
}

function updateSwimlaneId(element: ElementSchema, swimlaneId?: string) {
  element.swimlaneId = swimlaneId;
  for (const child of getElementChildren(element.children as ElementSchema[])) {
    updateSwimlaneId(child, swimlaneId);
  }
}
