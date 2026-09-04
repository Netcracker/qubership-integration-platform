import {
  ActionDifference,
  Chain,
  ConnectionRequest,
  CreateElementRequest,
  Dependency,
  Element,
  LibraryElement,
  LibraryElementProperty,
  MaskedField,
  PatchElementRequest,
  TransferElementRequest,
} from "@netcracker/qip-ui";
import {
  getChain,
  getDependencyId,
  getElement,
  getLibraryElementByType,
  getMainChain,
  getMaskedField,
  parseElement,
  parseMaskedField,
} from "./chainApiRead";
import {
  cloneElementSchema,
  findElementById,
  findElementByIdOrError,
  getElementChildren,
  LibraryElementQuantity,
  LibraryInputQuantity,
  replaceElementPlaceholders,
  resetPropertiesToDefault,
} from "./chainApiUtils";
import { Uri } from "vscode";
import { fileApi } from "./file";
import {
  Element as ElementSchema,
  DataType,
  Chain as ChainSchema,
} from "@netcracker/qip-schemas";
import {
  createSwimlane,
  deleteSwimlane,
  enrichElementWithSwimlaneId,
  isSwimlane,
  isTransferOutOfSwimlane,
  SWIMLANE_TYPE_NAME,
  swimlaneValidations,
  transferToSwimlaneValidations,
} from "./swimlaneUtils";
import { OrderedElementService } from "../api-services/OrderedElementService";

export async function updateChain(
  fileUri: Uri,
  chainId: string,
  chainRequest: Partial<Chain>,
): Promise<Chain> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const labels = chainRequest?.labels
    ?.filter((label) => !label.technical)
    .map((label) => label.name);

  chain.name = chainRequest.name !== undefined ? chainRequest.name : chain.name;
  chain.content.description =
    chainRequest.description !== undefined
      ? chainRequest.description
      : chain.content.description;
  chain.content.labels = labels !== undefined ? labels : chain.content.labels;
  chain.content.businessDescription =
    chainRequest.businessDescription !== undefined
      ? chainRequest.businessDescription
      : chain.content.businessDescription;
  chain.content.assumptions =
    chainRequest.assumptions !== undefined
      ? chainRequest.assumptions
      : chain.content.assumptions;
  chain.content.outOfScope =
    chainRequest.outOfScope !== undefined
      ? chainRequest.outOfScope
      : chain.content.outOfScope;
  chain.content.deployments =
    chainRequest.deployments !== undefined
      ? chainRequest.deployments
      : chain.content.deployments;
  chain.content.deployAction =
    chainRequest.deployAction !== undefined
      ? chainRequest.deployAction
      : chain.content.deployAction;

  await fileApi.writeMainChain(fileUri, chain);

  return await getChain(fileUri, chainId);
}

// The group container has no library entry of its own.
const CONTAINER_TYPE_NAME = "container";

/**
 * Tells whether a parent can hold the given child types, the way the runtime
 * catalog decides it: the parent must be a container, and a container that
 * declares `allowedChildren` accepts only the types it lists. A parent that
 * fails this test is connected to the elements instead of nesting them.
 */
async function acceptsChildren(
  parent: ElementSchema,
  childTypes: string[],
): Promise<boolean> {
  const parentType = parent.type as unknown as string;
  if (parentType === CONTAINER_TYPE_NAME) {
    return true;
  }

  const libraryData = await getLibraryElementByType(parentType);
  if (!libraryData.container) {
    return false;
  }

  const allowedChildren = libraryData.allowedChildren ?? {};
  return (
    Object.keys(allowedChildren).length === 0 ||
    childTypes.every((childType) => childType in allowedChildren)
  );
}

async function checkRestrictions(
  element: ElementSchema,
  elements: ElementSchema[],
) {
  const elementType = element.type as unknown as string;
  const libraryData =
    elementType === CONTAINER_TYPE_NAME
      ? undefined
      : await getLibraryElementByType(elementType);
  if (!libraryData) {
    return;
  }
  const parentElementId = findElementById(elements, element.id)?.parentId; // More consistent way instead of parentElementId field

  if (parentElementId) {
    if (!libraryData.allowedInContainers) {
      console.error(`Invalid parent for element`);
      throw Error("Invalid parent for element");
    }

    const parentElement = findElementById(elements, parentElementId)?.element;
    if (parentElement) {
      const libraryParentData =
        (parentElement.type as unknown as string) === CONTAINER_TYPE_NAME
          ? undefined
          : await getLibraryElementByType(
              parentElement.type as unknown as string,
            );

      if (libraryData.parentRestriction?.length > 0) {
        if (
          !libraryData.parentRestriction.find(
            (type) => type === (parentElement.type as unknown as string),
          )
        ) {
          console.error(`Invalid parent type for element`);
          throw Error("Invalid parent type for element");
        }
      }

      // Check for allowed children inside parent element
      if (
        libraryParentData?.allowedChildren &&
        Object.keys(libraryParentData.allowedChildren).length > 0
      ) {
        const amount = libraryParentData.allowedChildren[elementType];
        if (!amount) {
          console.error(`Invalid type for parent element`);
          throw Error("Invalid type for parent element");
        }

        if (
          amount === LibraryElementQuantity.ONE ||
          amount === LibraryElementQuantity.ONE_OR_ZERO
        ) {
          const actualAmount = (
            parentElement.children as ElementSchema[]
          )?.filter((e: ElementSchema) => e.type === element.type).length;

          if (
            actualAmount === undefined ||
            actualAmount > 1 ||
            (actualAmount === 0 && amount === LibraryElementQuantity.ONE)
          ) {
            console.error(
              `Incorrect amount of element type for parent element`,
            );
            throw Error("Incorrect amount of element type for parent element");
          }
        }
      }
    }
  } else {
    if (libraryData.parentRestriction?.length > 0) {
      console.error(`Invalid parent type for element`);
      throw Error("Invalid parent type for element");
    }
  }

  // Check if element doesn't have enough elements as children (in case of deletion)
  if (
    libraryData.allowedChildren &&
    Object.keys(libraryData.allowedChildren).length > 0
  ) {
    for (const childType in libraryData.allowedChildren) {
      if (
        libraryData.allowedChildren[childType] === LibraryElementQuantity.ONE ||
        libraryData.allowedChildren[childType] ===
          LibraryElementQuantity.ONE_OR_MANY
      ) {
        if (
          !(
            (element.children as ElementSchema[])?.filter(
              (e: ElementSchema) => (e.type as unknown as string) === childType,
            ).length > 0
          )
        ) {
          console.error(`Incorrect amount of children elements`);
          throw Error("Incorrect amount of children elements");
        }
      }
    }
  }
  // Can't check it after element add, i.e. if you add "Try" element it will be always empty
  // if (libraryData.mandatoryInnerElement && !(element.children?.length > 0)) {
  //     console.error(`Incorrect amount of children elements`);
  //     throw Error("Incorrect amount of children elements");
  // }
}

export async function updateElement(
  fileUri: Uri,
  chainId: string,
  elementId: string,
  elementRequest: PatchElementRequest,
): Promise<ActionDifference> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const chainElements = chain.content.elements as ElementSchema[];
  const elementWithParentId = findElementById(chainElements, elementId);
  let element: ElementSchema | undefined = elementWithParentId?.element;

  if (!element) {
    console.error(`ElementId not found`);
    throw Error("ElementId not found");
  }

  const isChangeParent =
    elementWithParentId?.parentId !== elementRequest.parentElementId;

  if (isChangeParent) {
    element = findAndRemoveElementById(chainElements, elementId)!;
  }

  let parentElement = undefined;
  if (elementRequest.parentElementId) {
    parentElement = findElementById(
      chainElements,
      elementRequest.parentElementId,
    );
    if (!parentElement) {
      console.error(`Parent ElementId not found`);
      throw Error("Parent ElementId not found");
    }
  }

  element.name = elementRequest.name;
  element.description = elementRequest.description;
  const diff = await new OrderedElementService(
    fileUri,
    chainId,
    chainElements,
  ).updateProperties(
    { element, parentElementId: elementWithParentId?.parentId },
    elementRequest,
  );
  (element as any).properties = elementRequest.properties;

  element.parentElementId = elementRequest.parentElementId;
  if (isChangeParent) {
    if (parentElement) {
      if (!(parentElement.element.children as ElementSchema[])?.length) {
        parentElement.element.children = [];
      }
      (parentElement.element.children as ElementSchema[]).push(element);
    } else {
      (chain.content.elements as ElementSchema[]).push(element);
    }
  }

  await checkRestrictions(element, chain.content.elements as ElementSchema[]);

  await writeElementProperties(fileUri, element);
  await fileApi.writeMainChain(fileUri, chain);

  const updatedElement = await getElement(fileUri, chainId, elementId);
  if (diff?.updatedElements?.length) {
    diff.updatedElements.push(updatedElement);
    return diff;
  }

  return {
    updatedElements: [updatedElement],
  };
}

export async function transferElement(
  fileUri: Uri,
  chainId: string,
  elementRequest: TransferElementRequest,
): Promise<ActionDifference> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const chainElements = chain.content.elements as ElementSchema[];

  // A target that cannot hold the elements gets connected to them instead, and
  // nothing moves.
  if (elementRequest.parentId) {
    const targetElement = findElementByIdOrError(
      chainElements,
      elementRequest.parentId,
    ).element;
    const transferredTypes = elementRequest.elements.map(
      (elementId) =>
        findElementByIdOrError(chainElements, elementId).element
          .type as unknown as string,
    );
    if (!(await acceptsChildren(targetElement, transferredTypes))) {
      return await connectToTransferTarget(
        fileUri,
        chain,
        targetElement,
        elementRequest.elements,
      );
    }
    validateNotTransferIntoItself(
      chainElements,
      targetElement,
      elementRequest.elements,
    );
  }

  // A move that stays among group containers and the canvas leaves every
  // dependency drawable, so the runtime catalog skips the dependency check for
  // it and runs the check only when an element group is on either end.
  const isGroupContainerTransfer =
    elementRequest.elements.every((elementId) =>
      isRootOrGroupContainer(
        chainElements,
        findElementById(chainElements, elementId)?.parentId,
      ),
    ) && isRootOrGroupContainer(chainElements, elementRequest.parentId);

  for (const elementId of elementRequest.elements) {
    let element: ElementSchema | undefined = findElementByIdOrError(
      chainElements,
      elementId,
    ).element;

    if (isTransferOutOfSwimlane(elementRequest, element, chain)) {
      continue;
    }

    transferToSwimlaneValidations(chain, element, elementRequest);

    element = findAndRemoveElementById(chainElements, elementId)!;

    if (!isGroupContainerTransfer) {
      validateNoOutsideDependencies(
        chain.content.dependencies as Dependency[], // TODO change to dependency schema
        elementId,
        elementRequest.elements,
      );
    }

    let parentElement = undefined;
    if (elementRequest.parentId) {
      parentElement = findElementById(chainElements, elementRequest.parentId);
      if (!parentElement) {
        console.error(`Parent ElementId not found`);
        throw Error("Parent ElementId not found");
      }
    }

    element.parentElementId = elementRequest.parentId || undefined;
    if (parentElement) {
      if (!(parentElement.element.children as ElementSchema[])?.length) {
        parentElement.element.children = [];
      }
      (parentElement.element.children as ElementSchema[]).push(element);
    } else {
      chainElements.push(element);
    }

    await checkRestrictions(element, chainElements);
  }

  await fileApi.writeMainChain(fileUri, chain);

  const updatedElements: Element[] = [];
  for (const elementId of elementRequest.elements) {
    updatedElements.push(await getElement(fileUri, chainId, elementId));
  }

  return {
    updatedElements: updatedElements,
  };
}

function validateNoOutsideDependencies(
  dependencies: Dependency[] | undefined,
  elementId: string,
  transferredIds: string[],
) {
  dependencies?.forEach((dependency: Dependency) => {
    if (dependency.from === elementId || dependency.to === elementId) {
      if (
        !transferredIds.includes(dependency.from) ||
        !transferredIds.includes(dependency.to)
      ) {
        const message = `Element ${elementId} has dependencies outside the transferred elements`;
        console.error(message);
        throw Error(message);
      }
    }
  });
}

/**
 * Tells whether the given parent is the canvas or a group container, the two
 * places between which the runtime catalog moves elements without checking
 * their dependencies.
 */
function isRootOrGroupContainer(
  elements: ElementSchema[],
  parentId: string | null | undefined,
): boolean {
  if (!parentId) {
    return true;
  }

  return (
    (findElementById(elements, parentId)?.element.type as unknown as string) ===
    CONTAINER_TYPE_NAME
  );
}

function validateNotTransferIntoItself(
  chainElements: ElementSchema[],
  target: ElementSchema,
  transferredIds: string[],
) {
  let current = findElementById(chainElements, target.id);
  while (current) {
    if (transferredIds.includes(current.element.id)) {
      throw Error("Element cannot be transferred into itself");
    }
    current = current.parentId
      ? findElementById(chainElements, current.parentId)
      : undefined;
  }
}

async function checkAllowedInContainers(element: ElementSchema) {
  const elementType = element.type as unknown as string;
  if (elementType === CONTAINER_TYPE_NAME) {
    return;
  }

  const libraryData = await getLibraryElementByType(elementType);
  if (!libraryData.allowedInContainers) {
    const message = `The ${libraryData.name} element cannot be inside a container`;
    console.error(message);
    throw Error(message);
  }
}

/**
 * Connects a transfer target to the transferred elements instead of nesting them,
 * the way the runtime catalog does when the target is not a container. Elements
 * that already have an input dependency keep it and are left alone.
 */
async function connectToTransferTarget(
  fileUri: Uri,
  chain: ChainSchema,
  target: ElementSchema,
  transferredIds: string[],
): Promise<ActionDifference> {
  const chainElements = chain.content.elements as ElementSchema[];
  const dependencies = chain.content.dependencies as Dependency[] | undefined; // TODO change to dependency schema

  validateNotTransferIntoItself(chainElements, target, transferredIds);

  const createdDependencies: Dependency[] = [];
  for (const elementId of transferredIds) {
    const element = findElementByIdOrError(chainElements, elementId).element;
    await checkAllowedInContainers(element);
    validateNoOutsideDependencies(dependencies, elementId, transferredIds);

    if (dependencies?.some((dependency) => dependency.to === elementId)) {
      continue;
    }
    createdDependencies.push(await addDependency(chain, target.id, elementId));
  }

  await fileApi.writeMainChain(fileUri, chain);

  return {
    createdDependencies: createdDependencies.map(withDependencyId),
  };
}

function getOrCreatePropertyFilename(
  type: string,
  propertyNames: string[] | undefined,
  exportFileExtension: string | undefined,
  id: string,
) {
  let prefix: string;
  if (!propertyNames || !exportFileExtension) {
    throw new Error(
      `Property names and exportFileExtension should be presented`,
    );
  }
  if (type.startsWith("mapper")) {
    prefix = propertyNames.length === 1 ? propertyNames[0] : "mapper";
  } else {
    prefix = propertyNames.length === 1 ? propertyNames[0] : "properties";
  }

  return `${prefix}-${id}.${exportFileExtension}`;
}

async function writeElementProperties(
  fileUri: Uri,
  element: ElementSchema,
): Promise<void> {
  async function handleServiceCallProperty(beforeAfterBlock: any) {
    const propertiesFilenameId =
      (beforeAfterBlock.id ? beforeAfterBlock.id + "-" : "") + element.id;
    if (beforeAfterBlock.type === "script") {
      beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(
        beforeAfterBlock.type,
        ["script"],
        "groovy",
        propertiesFilenameId,
      );
      await fileApi.writePropertyFile(
        fileUri,
        beforeAfterBlock.propertiesFilename,
        beforeAfterBlock["script"],
      );
      delete beforeAfterBlock["script"];
    } else if (beforeAfterBlock.type?.startsWith("mapper")) {
      if (beforeAfterBlock.type === "mapper") {
        console.error(
          "Attempt to save Deprecated element failed as it is not supported",
        );
        throw Error("Deprecated Mapper element is not supported");
      }
      beforeAfterBlock.propertiesFilename = getOrCreatePropertyFilename(
        beforeAfterBlock.type,
        ["mappingDescription"],
        "json",
        propertiesFilenameId,
      );
      const property: any = JSON.stringify(
        { mappingDescription: beforeAfterBlock["mappingDescription"] },
        null,
        2,
      );
      await fileApi.writePropertyFile(
        fileUri,
        beforeAfterBlock.propertiesFilename,
        property,
      );
      delete beforeAfterBlock["mappingDescription"];
    }
  }

  const elementType = element.type as unknown as string;
  if ((element.properties as any)?.propertiesToExportInSeparateFile) {
    const elementProperties = element.properties as any;
    const propertyNames: string[] | undefined =
      elementProperties.propertiesToExportInSeparateFile
        ?.split(",")
        .map(function (item: string) {
          return item.trim();
        });
    elementProperties.propertiesFilename = getOrCreatePropertyFilename(
      elementType,
      propertyNames,
      elementProperties.exportFileExtension,
      element.id,
    );
    if (elementProperties.exportFileExtension === "json" && propertyNames) {
      const properties: any = {};
      for (const propertyName of propertyNames) {
        properties[propertyName] = elementProperties[propertyName];
      }
      await fileApi.writePropertyFile(
        fileUri,
        elementProperties.propertiesFilename,
        JSON.stringify(properties, null, 2),
      );
      for (const propertyName of propertyNames) {
        delete elementProperties[propertyName];
      }
    } else {
      await fileApi.writePropertyFile(
        fileUri,
        elementProperties.propertiesFilename,
        elementProperties[
          elementProperties.propertiesToExportInSeparateFile as string
        ] as string,
      );
      delete elementProperties[
        elementProperties.propertiesToExportInSeparateFile as string
      ];
    }
  }

  if (elementType === "service-call") {
    const elementProperties = element.properties as any; // WA before fix of schemas compilation missing service call properties
    if (Array.isArray(elementProperties.after)) {
      for (const afterBlock of elementProperties.after) {
        await handleServiceCallProperty(afterBlock);
      }
    }
    if (elementProperties.before) {
      await handleServiceCallProperty(elementProperties.before);
    }
  }
}

export async function getDefaultElementByType(
  chainId: string,
  elementRequest: CreateElementRequest,
): Promise<ElementSchema> {
  return getDefaultElement(
    chainId,
    elementRequest.type,
    elementRequest.parentElementId,
  );
}

export async function getDefaultElement(
  chainId: string,
  type: string,
  parentId?: string,
): Promise<ElementSchema> {
  const elementId = crypto.randomUUID();
  const libraryData = await getLibraryElementByType(type);

  let children: ElementSchema[] | undefined = undefined;
  if (
    libraryData.allowedChildren &&
    Object.keys(libraryData.allowedChildren).length
  ) {
    children = [];
    for (const childType in libraryData.allowedChildren) {
      children.push(
        await getDefaultElementByType(chainId, {
          type: childType,
          parentElementId: elementId,
        }),
      );
    }
  }

  const element: ElementSchema = {
    description: "",
    id: elementId,
    mandatoryChecksPassed: false,
    name: libraryData.title,
    properties: await getDefaultPropertiesForElement(libraryData.properties),
    type: type as unknown as DataType,
    children: children,
    parentElementId: parentId,
  };

  if (type === "checkpoint" || type === "chain-trigger-2") {
    replaceElementPlaceholders(element.properties, chainId, elementId);
  }

  return element;
}

export async function createElement(
  mainFolderUri: Uri,
  chainId: string,
  elementRequest: CreateElementRequest,
): Promise<ActionDifference> {
  const chain = await getMainChain(mainFolderUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  if (elementRequest.type === SWIMLANE_TYPE_NAME) {
    return await createSwimlane(mainFolderUri, chain, elementRequest);
  }

  if (!chain.content.elements) {
    chain.content.elements = [];
  }
  const chainElements = chain.content.elements as ElementSchema[];

  // A parent that cannot hold the new element gets connected to it: the element
  // is created next to the parent, in the parent's swimlane.
  const parentElement = elementRequest.parentElementId
    ? findElementByIdOrError(chainElements, elementRequest.parentElementId)
        .element
    : undefined;
  const connectToParent =
    parentElement !== undefined &&
    !(await acceptsChildren(parentElement, [elementRequest.type]));
  if (connectToParent) {
    elementRequest = {
      ...elementRequest,
      swimlaneId: parentElement!.swimlaneId as string,
    };
  }

  const element = await getDefaultElementByType(chainId, elementRequest);
  if (connectToParent) {
    element.parentElementId = undefined;
  }

  const chainDiff: ActionDifference = {
    createdElements: [],
    updatedElements: [],
  };
  if (
    !(await insertElement(
      mainFolderUri,
      chain,
      chainElements,
      undefined,
      element,
      chainDiff,
      elementRequest,
    ))
  ) {
    chainElements.push(element);
  }

  await checkRestrictions(element, chainElements);

  await new OrderedElementService(
    mainFolderUri,
    chainId,
    chainElements,
  ).updatePriority({
    element,
    parentElementId: element.parentElementId as string | undefined,
  });

  const newDependency = connectToParent
    ? await addDependency(chain, parentElement!.id, element.id)
    : undefined;

  await writeElementProperties(mainFolderUri, element);
  await fileApi.writeMainChain(mainFolderUri, chain);

  chainDiff.createdElements?.push(
    await getElement(mainFolderUri, chainId, element.id),
  );
  if (newDependency) {
    chainDiff.createdDependencies = [withDependencyId(newDependency)];
  }
  return chainDiff;
}

async function insertElement(
  fileUri: Uri,
  chain: ChainSchema,
  elements: ElementSchema[],
  parentOfElements: string | undefined,
  newElement: ElementSchema,
  chainDiff: ActionDifference,
  elementRequest: CreateElementRequest,
): Promise<boolean> {
  swimlaneValidations(chain, newElement, elementRequest);

  if (!newElement.parentElementId) {
    await enrichElementWithSwimlaneId(
      fileUri,
      chain,
      elementRequest,
      newElement,
      chainDiff,
    );

    elements.push(newElement);
    return true;
  }

  for (const element of elements) {
    if (element.id === newElement.parentElementId) {
      if (!element.children) {
        element.children = [];
      }
      newElement.swimlaneId = element.swimlaneId;
      (element.children as ElementSchema[]).push(newElement);
      chainDiff.updatedElements?.push(
        await parseElement(fileUri, element, chain.id, parentOfElements),
      );
      return true;
    }

    if (
      element.children &&
      (await insertElement(
        fileUri,
        chain,
        element.children as ElementSchema[],
        element.id,
        newElement,
        chainDiff,
        elementRequest,
      ))
    ) {
      return true; // inserted in nested children
    }
  }

  return false; // parent not found
}

function getDefaultPropertiesForElement(libraryProperties: any): any {
  let properties: any = {};
  for (const propertyType in libraryProperties) {
    properties = {
      ...properties,
      ...getDefaultTypedProperties(libraryProperties[propertyType]),
    };
  }
  return properties;
}

function getDefaultTypedProperties(
  propertiesData: LibraryElementProperty[],
): any {
  const result: any = {};
  for (const property of propertiesData) {
    if (property.default) {
      let defaultValue: any = String(property.default);
      switch (property.type) {
        case "boolean":
          defaultValue = defaultValue === "true";
          break;
        case "number":
          defaultValue = parseFloat(defaultValue);
          break;
      }
      result[property.name] = defaultValue;
    }
  }
  return result;
}

export function findAndRemoveElementById(
  elements: ElementSchema[] | undefined,
  elementId: string,
): ElementSchema | undefined {
  if (!elements) {
    return undefined;
  }
  const index = elements.findIndex((e) => e.id === elementId);
  if (index !== -1) {
    return elements.splice(index, 1)[0];
  }

  for (const element of elements) {
    const found = findAndRemoveElementById(
      element.children as ElementSchema[],
      elementId,
    );
    if (found) {
      return found;
    }
  }

  return undefined;
}

async function deleteElementsPropertyFiles(
  fileUri: Uri,
  removedElements: any[],
) {
  async function handleServiceCallProperty(beforeAfterBlock: any) {
    if (beforeAfterBlock.type === "script") {
      beforeAfterBlock["script"] = await fileApi.removeFile(
        fileUri,
        beforeAfterBlock.propertiesFilename,
      );
    } else if (beforeAfterBlock.type?.startsWith("mapper")) {
      await fileApi.removeFile(fileUri, beforeAfterBlock.propertiesFilename);
    }
  }

  for (const element of removedElements) {
    if (element.properties?.propertiesToExportInSeparateFile) {
      await fileApi.removeFile(fileUri, element.properties.propertiesFilename);
    }

    if (element.type === "service-call") {
      if (Array.isArray(element.properties.after)) {
        for (const afterBlock of element.properties.after) {
          await handleServiceCallProperty(afterBlock);
        }
      }
      if (element.properties.before) {
        await handleServiceCallProperty(element.properties.before);
      }
    }

    if (element.children?.length) {
      await deleteElementsPropertyFiles(fileUri, element.children);
    }
  }
}

export async function deleteElements(
  fileUri: Uri,
  chainId: string,
  elementIds: string[],
): Promise<ActionDifference> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const chainDiff: ActionDifference = {
    removedElements: [],
    updatedElements: [],
  };
  const chainElements = chain.content.elements as ElementSchema[];
  for (const elementId of elementIds) {
    const findElementResult = findElementById(chainElements, elementId);
    const parentElementId = findElementResult?.parentId;
    const element = findElementResult?.element;

    if (!element) {
      console.error(`ElementId not found`);
      throw Error("ElementId not found");
    } else if (isSwimlane(element)) {
      const diff = await deleteSwimlane(fileUri, element, chain);
      chainDiff.removedElements?.push(...(diff.removedElements as any[]));
      chainDiff.updatedElements?.push(...(diff.updatedElements as any[]));
      continue;
    }

    await new OrderedElementService(
      fileUri,
      chainId,
      chainElements,
    ).removeElementIfOrderedAndMergeDiff(
      { element, parentElementId },
      chainDiff,
    );

    const removedElement = findAndRemoveElementById(chainElements, elementId)!;

    for (const childElement of getElementChildren(
      removedElement.children as ElementSchema[],
    )) {
      await deleteDependenciesForElement(
        childElement.id,
        chain.content.dependencies as Dependency[],
      ); // TODO change to dependency schema
      chainDiff.removedElements?.push(childElement as any);
    }

    await deleteDependenciesForElement(
      elementId,
      chain.content.dependencies as Dependency[],
    ); // TODO change to dependency schema
    chainDiff.removedElements?.push(removedElement as any);

    const parentElement = parentElementId
      ? findElementById(chainElements, parentElementId)?.element
      : undefined;
    if (parentElement) {
      await checkRestrictions(parentElement, chainElements);
    }
  }

  await fileApi.writeMainChain(fileUri, chain);
  await deleteElementsPropertyFiles(
    fileUri,
    chainDiff.removedElements as any[],
  );

  return chainDiff;
}

async function deleteDependenciesForElement(
  elementId: string,
  dependencies: Dependency[],
) {
  // TODO change to dependency schema
  if (!dependencies?.length) {
    return;
  }
  for (let i = dependencies.length - 1; i >= 0; i--) {
    if (
      dependencies[i].from === elementId ||
      dependencies[i].to === elementId
    ) {
      dependencies.splice(i, 1);
    }
  }
}

/**
 * Validates a connection and appends it to the chain. The returned dependency is
 * the stored one, so it carries no `id`: the id is derived from the endpoints and
 * must stay out of the chain file. Stamp it with `withDependencyId` once the chain
 * has been written.
 */
async function addDependency(
  chain: ChainSchema,
  from: string,
  to: string,
): Promise<Dependency> {
  if (!chain.content.dependencies) {
    chain.content.dependencies = [];
  }
  const chainDependencies = chain.content.dependencies as Dependency[]; // TODO change to dependency schema
  const chainElements = chain.content.elements as ElementSchema[];

  const elementFrom = findElementById(chainElements, from)?.element;
  if (!elementFrom) {
    console.error(`ElementId from not found`);
    throw Error("ElementId from not found");
  }
  const libraryDataFrom = await getLibraryElementByType(
    elementFrom.type as unknown as string,
  );
  if (!libraryDataFrom.outputEnabled) {
    console.error(`Element from does not allow output connections`);
    throw Error("Element from does not allow output connections");
  }

  const elementTo = findElementById(chainElements, to)?.element;
  if (!elementTo) {
    console.error(`ElementId to not found`);
    throw Error("ElementId to not found");
  }
  const libraryDataTo = await getLibraryElementByType(
    elementTo.type as unknown as string,
  );
  if (!libraryDataTo.inputEnabled) {
    console.error(`Element to does not allow output connections`);
    throw Error("Element to does not allow output connections");
  }
  if (
    libraryDataTo.inputQuantity === LibraryInputQuantity.ONE &&
    chainDependencies?.find((d: Dependency) => d.to === to)
  ) {
    console.error(`Element to does not allow another connections`);
    throw Error("Element to does not allow another connections");
  }

  const dependency: Dependency | undefined = chainDependencies?.find(
    (dependency: Dependency) =>
      dependency.from === from && dependency.to === to,
  );
  if (dependency) {
    console.error(`Connection already exist`);
    throw Error("Connection already exist");
  }
  const newDependency: any = { from, to };

  chainDependencies.push(newDependency);

  return newDependency;
}

// TODO Change to read dependency from file
function withDependencyId(dependency: Dependency): Dependency {
  return { ...dependency, id: getDependencyId(dependency) };
}

export async function createConnection(
  fileUri: Uri,
  chainId: string,
  connectionRequest: ConnectionRequest,
): Promise<ActionDifference> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const newDependency = await addDependency(
    chain,
    connectionRequest.from,
    connectionRequest.to,
  );

  await fileApi.writeMainChain(fileUri, chain);

  return {
    createdDependencies: [withDependencyId(newDependency)],
  };
}

export async function deleteConnections(
  fileUri: Uri,
  chainId: string,
  connectionIds: string[],
): Promise<ActionDifference> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const removedConnections: any[] = [];

  for (const connectionId of connectionIds) {
    let dependency: Dependency | undefined = (
      chain.content.dependencies as Dependency[]
    )?.find(
      (
        dependency: Dependency, // TODO change to dependency schema
      ) => getDependencyId(dependency) === connectionId,
    );
    if (!dependency) {
      console.error(`Connection not found`);
      throw Error("Connection not found");
    }

    let index = (chain.content.dependencies as Dependency[]).findIndex(
      (d: Dependency) => d === dependency,
    ); // TODO change to dependency schema
    (chain.content.dependencies as Dependency[]).splice(index, 1);

    dependency["id"] = getDependencyId(dependency);
    removedConnections.push(dependency);
  }

  await fileApi.writeMainChain(fileUri, chain);

  return {
    removedDependencies: [...removedConnections],
  };
}

export async function deleteMaskedFields(
  fileUri: Uri,
  chainId: string,
  maskedFieldIds: string[],
): Promise<void> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  // TODO change to maskedfield type
  for (const maskedFieldId of maskedFieldIds) {
    let index = (chain.content.maskedFields as [])?.findIndex(
      (mf: any) => mf.id === maskedFieldId,
    );
    if (index) {
      (chain.content.maskedFields as []).splice(index, 1);
    }
  }

  await fileApi.writeMainChain(fileUri, chain);
}

export async function updateMaskedField(
  fileUri: Uri,
  id: string,
  chainId: string,
  changes: Partial<MaskedField>,
): Promise<MaskedField> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }
  let maskedField = getMaskedField(chain, id);

  maskedField.name = changes.name;

  await fileApi.writeMainChain(fileUri, chain);

  return parseMaskedField(chain, id);
}

export async function createMaskedField(
  fileUri: Uri,
  chainId: string,
  changes: Partial<MaskedField>,
): Promise<MaskedField> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  if (!chain.content.maskedFields) {
    chain.content.maskedFields = [];
  }

  const id = crypto.randomUUID();
  // @ts-ignore Will be removed when DependencySchema will be introduced
  chain.content.maskedFields.push({
    id: id,
    name: changes.name,
  });

  await fileApi.writeMainChain(fileUri, chain);

  return parseMaskedField(chain, id);
}

// Characters forbidden in a group segment (mirrors the schema `metaInfo.group` pattern).
const FORBIDDEN_SEGMENT_CHARS = /[/:*?"<>|,;\\]/g;

export async function changeFolder(
  fileUri: Uri,
  chainId: string,
  folders: string,
): Promise<void> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const group = trimSlashes(folders.trim())
    .split("/")
    .map((segment) => segment.trim())
    .filter((segment) => segment.length > 0)
    .map((segment) => segment.replace(FORBIDDEN_SEGMENT_CHARS, "-"))
    .join("/");

  if (group) {
    chain.metaInfo = { ...chain.metaInfo, group };
  } else if (chain.metaInfo) {
    // Clear only the group, keep any other metaInfo fields.
    delete chain.metaInfo.group;
    if (Object.keys(chain.metaInfo).length === 0) {
      delete chain.metaInfo;
    }
  }

  // Drop the deprecated nested folder structure if a legacy file still carries it.
  if (chain.content?.folder) {
    delete chain.content.folder;
  }

  return await fileApi.writeMainChain(fileUri, chain);
}

function trimSlashes(value: string): string {
  while (value.startsWith("/")) {
    value = value.slice(1);
  }
  while (value.endsWith("/")) {
    value = value.slice(0, -1);
  }
  return value;
}

export async function groupElements(
  fileUri: Uri,
  chainId: string,
  elementIds: string[],
): Promise<Element> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const groupedElements: any[] = [];
  const chainElements = chain.content.elements as ElementSchema[];
  for (const elementId of elementIds) {
    const parentElementId = findElementById(chainElements, elementId)?.parentId;
    if (parentElementId) {
      console.error(`Elements with non-null parent cannot be grouped`);
      throw Error("Elements with non-null parent cannot be grouped");
    }
    const element = findAndRemoveElementById(chainElements, elementId);
    if (!element) {
      console.error(`ElementId not found`);
      throw Error("ElementId not found");
    }
    groupedElements.push(element);
  }

  const containerElement: ElementSchema = {
    id: crypto.randomUUID(),
    name: "Container",
    type: "container" as unknown as DataType,
    children: groupedElements,
    swimlaneId:
      groupedElements?.length > 0 ? groupedElements[0].swimlaneId : undefined,
  };
  chainElements.push(containerElement);

  for (const element of groupedElements) {
    await checkRestrictions(element, chainElements);
  }

  await fileApi.writeMainChain(fileUri, chain);

  return await getElement(fileUri, chainId, containerElement.id);
}

export async function ungroupElements(
  fileUri: Uri,
  chainId: string,
  elementId: string,
): Promise<Element[]> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw Error("ChainId mismatch");
  }

  const chainElements = chain.content.elements as ElementSchema[];
  const containerElement = findAndRemoveElementById(chainElements, elementId);
  if (!containerElement) {
    console.error(`ElementId not found`);
    throw Error("ElementId not found");
  }
  const containerChildren = containerElement.children as ElementSchema[];
  chainElements.push(...containerChildren);

  await fileApi.writeMainChain(fileUri, chain);

  const updatedElements: Element[] = [];
  for (const element of containerChildren) {
    updatedElements.push(await getElement(fileUri, chainId, element.id));
  }

  return updatedElements;
}

export async function cloneElements(
  fileUri: Uri,
  chainId: string,
  ids: string[],
  containerId?: string,
): Promise<Element[]> {
  const chain = await getMainChain(fileUri);
  if (chain.id !== chainId) {
    console.error(`ChainId mismatch`);
    throw new Error("ChainId mismatch");
  }

  const newElementIds: string[] = [];

  const chainElements: ElementSchema[] = chain.content
    .elements as ElementSchema[];

  const containerElementSchema: ElementSchema | undefined = containerId
    ? findElementByIdOrError(chainElements, containerId).element
    : undefined;

  for (const elementId of ids) {
    const elementSchema = findElementByIdOrError(
      chainElements,
      elementId,
    )?.element;

    const clone: ElementSchema = cloneElementSchema(elementSchema);
    const libraryElement: LibraryElement = await getLibraryElementByType(
      clone.type as unknown as string,
    );

    resetPropertiesToDefault(chainId, clone, libraryElement);

    if (containerElementSchema) {
      clone.parentElementId = containerId;
      if (!containerElementSchema.children) {
        containerElementSchema.children = [];
      }
      (containerElementSchema.children as ElementSchema[]).push(clone);
    } else {
      clone.parentElementId = undefined;
      chainElements.push(clone);
    }

    newElementIds.push(clone.id);
  }
  await fileApi.writeMainChain(fileUri, chain);

  const result: Element[] = [];
  for (const elementId of newElementIds) {
    result.push(await getElement(fileUri, chainId, elementId));
  }

  return result;
}
