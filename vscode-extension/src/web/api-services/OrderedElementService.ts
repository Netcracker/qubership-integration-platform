import { Uri } from "vscode";
import { Element as ElementSchema } from "@netcracker/qip-schemas";
import {
  getLibraryElementByType,
  parseElement,
} from "../response/chainApiRead";
import { OrderedElementUtils } from "./OrderedElementUtils";
import { ActionDifference, PatchElementRequest } from "@netcracker/qip-ui";
import { findElementById } from "../response/chainApiUtils";
import { getType } from "./elementUtils";

export class OrderedElementService {
  constructor(
    private readonly chainFileUri: Uri,
    private readonly chainId: string,
    private readonly chainElements: ElementSchema[],
  ) {}

  async updatePriority(element: ElementSchema) {
    if (await OrderedElementService.isOrdered(element)) {
      await this.calculatePriority(element);
      return;
    }

    const libraryData = await getLibraryElementByType(getType(element));

    if (libraryData.container && element.children) {
      for (const child of element.children as ElementSchema[]) {
        if (await OrderedElementService.isOrdered(child)) {
          await this.calculatePriority(child);
        }
      }
    }
  }

  async calculatePriority(element: ElementSchema) {
    const orderedElementUtils = await OrderedElementUtils.create(element);
    const orderedElements = orderedElementUtils.extractOtherOrderedElements(
      this.getParentElement(element),
    );

    const orderNumber: number = orderedElements.filter((orderedElement) => {
      const currentOrderNumber: number =
        orderedElementUtils.getPriority(orderedElement);
      return (
        currentOrderNumber >= 0 && currentOrderNumber < orderedElements.length
      );
    }).length;
    orderedElementUtils.updatePriority(orderNumber);
  }

  static async isOrdered(element: ElementSchema): Promise<boolean> {
    const libraryElement = await getLibraryElementByType(getType(element));

    return libraryElement.ordered && element.parentElementId !== null;
  }

  async updateProperties(
    element: ElementSchema,
    elementRequest: PatchElementRequest,
  ): Promise<ActionDifference | undefined> {
    if (
      elementRequest.parentElementId &&
      elementRequest.parentElementId === element.parentElementId
    ) {
      if (await OrderedElementService.isOrdered(element)) {
        const orderedElementUtils = await OrderedElementUtils.create(element);
        const newPriority = orderedElementUtils.getPriorityOrUndefined(
          elementRequest.properties,
        );
        if (newPriority !== undefined) {
          return await this.changePriority(orderedElementUtils, newPriority);
        }
      }
    }
  }

  async changePriority(
    utils: OrderedElementUtils,
    newPriority: number,
  ): Promise<ActionDifference> {
    if (newPriority < 0) {
      throw new Error("Priority cannot be a negative number");
    }

    const chainDiff: ActionDifference = { updatedElements: [] };
    const currentPriority: number = utils.getPriority();

    if (currentPriority !== newPriority) {
      const parentElement = this.getParentElement(utils.element);
      const sortedElements = utils.extractSortedOrderedElements(parentElement);

      const currentPriorityIndex = utils.getIndex(sortedElements);
      let newPriorityIndex = utils.getIndex(sortedElements, newPriority);

      utils.updatePriority(newPriority);

      let elementsToUpdate = [];
      let priorityFunction: (currentPriority: number) => number;

      if (newPriority > currentPriority) {
        if (newPriorityIndex === -1) {
          if (currentPriorityIndex + 1 >= sortedElements.length) {
            return chainDiff;
          }
          const elements = sortedElements
            .map((element) => utils.getPriority(element))
            .filter((priority) => priority < sortedElements.length);
          newPriorityIndex =
            elements.length > 0 ? Math.max(...elements) : currentPriorityIndex;
        }
        elementsToUpdate = sortedElements.slice(
          currentPriorityIndex + 1,
          newPriorityIndex + 1,
        );
        priorityFunction = (currentPriority: number) => currentPriority - 1;
      } else {
        if (newPriorityIndex === -1) {
          return chainDiff;
        }
        elementsToUpdate = sortedElements.slice(
          newPriorityIndex,
          currentPriorityIndex,
        );
        priorityFunction = (currentPriority: number) => currentPriority + 1;
      }

      for (const elementToUpdate of elementsToUpdate) {
        const priority: number = utils.getPriority(elementToUpdate);
        if (priority < sortedElements.length) {
          utils.updatePriority(priorityFunction(priority), elementToUpdate);
          chainDiff.updatedElements?.push(
            await parseElement(
              this.chainFileUri,
              elementToUpdate,
              this.chainId,
              parentElement.id,
            ),
          );
        }
      }
    }

    return chainDiff;
  }

  async removeElementIfOrderedAndMergeDiff(
    element: ElementSchema,
    chainDiff: ActionDifference,
  ): Promise<void> {
    const diff = await this.removeElementIfOrdered(element);
    diff.updatedElements?.forEach((updatedElement) => {
      if (
        !chainDiff.updatedElements?.some((el) => el.id === updatedElement.id)
      ) {
        chainDiff.updatedElements?.push(updatedElement);
      }
    });
  }

  private async removeElementIfOrdered(
    element: ElementSchema,
  ): Promise<ActionDifference> {
    const chainDiff: ActionDifference = {
      updatedElements: [],
    };
    if (await OrderedElementService.isOrdered(element)) {
      const utils = await OrderedElementUtils.create(element);
      const currentPriority: number = utils.getPriority();

      const parentElement = this.getParentElement(element);

      if (
        currentPriority < (parentElement.children as ElementSchema[]).length
      ) {
        const sortedElements =
          utils.extractSortedOrderedElements(parentElement);
        const currentPriorityIntex = utils.getIndex(sortedElements);
        const lastPriorityIntex: number =
          sortedElements.filter(
            (el) => utils.getPriority(el) < sortedElements.length,
          ).length - 1;

        const elementsToUpdate = sortedElements.slice(
          currentPriorityIntex + 1,
          lastPriorityIntex + 1,
        );
        for (const elementToUpdate of elementsToUpdate) {
          const priority = utils.getPriority(elementToUpdate);
          utils.updatePriority(priority - 1, elementToUpdate);
          chainDiff.updatedElements?.push(
            await parseElement(
              this.chainFileUri,
              elementToUpdate,
              this.chainId,
              parentElement.id,
            ),
          );
        }
      }
    }

    return chainDiff;
  }

  private getParentElement(element: ElementSchema): ElementSchema {
    return findElementById(
      this.chainElements,
      element.parentElementId as string,
    )?.element!;
  }
}
