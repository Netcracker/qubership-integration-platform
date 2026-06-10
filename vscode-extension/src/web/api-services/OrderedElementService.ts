import { Uri } from "vscode";
import { Element as ElementSchema } from "@netcracker/qip-schemas";
import {
  getLibraryElementByType,
  parseElement,
} from "../response/chainApiRead";
import { OrderedElementUtils } from "./OrderedElementUtils";
import { ActionDifference, PatchElementRequest } from "@netcracker/qip-ui";
import { findElementById } from "../response/chainApiUtils";
import { ElementWithParentId, getType } from "./elementUtils";

export class OrderedElementService {
  constructor(
    private readonly chainFileUri: Uri,
    private readonly chainId: string,
    private readonly chainElements: ElementSchema[],
  ) {}

  async updatePriority(element: ElementWithParentId) {
    if (await OrderedElementService.isOrdered(element)) {
      await this.calculatePriority(element);
      return;
    }

    const libraryData = await getLibraryElementByType(getType(element.element));

    const elementSchema = element.element;
    if (libraryData.container && elementSchema.children) {
      for (const child of elementSchema.children as ElementSchema[]) {
        const childElement = {element: child, parentElementId: elementSchema.id};
        if (await OrderedElementService.isOrdered(childElement)) {
          await this.calculatePriority(childElement);
        }
      }
    }
  }

  async calculatePriority(element: ElementWithParentId) {
    const orderedElementUtils = await OrderedElementUtils.create(element);
    const orderedElements = orderedElementUtils.extractOtherOrderedElements(
      this.getParentElement(element.parentElementId!),
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

  static async isOrdered(element: ElementWithParentId): Promise<boolean> {
    const libraryElement = await getLibraryElementByType(getType(element.element));

    return element.parentElementId !== undefined && libraryElement.ordered;
  }

  async updateProperties(
    element: ElementWithParentId,
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

  private async changePriority(
    utils: OrderedElementUtils,
    newPriority: number,
  ): Promise<ActionDifference> {
    if (newPriority < 0) {
      throw new Error("Priority cannot be a negative number");
    }

    const chainDiff: ActionDifference = { updatedElements: [] };
    const currentPriority: number = utils.getPriority();

    if (currentPriority !== newPriority) {
      const parentElement = this.getParentElement(utils.parentElementId);
      const sortedElements = utils.extractSortedOrderedElements(parentElement);

      const currentPriorityIndex = utils.getIndex(sortedElements);

      const hasExactPriorityMatch: boolean = sortedElements.some(
        (element) => utils.getPriority(element) === newPriority,
      );

      let targetIndex = utils.getIndexToInsert(sortedElements, newPriority);

      utils.updatePriority(newPriority);

      if (hasExactPriorityMatch) {
        let elementsToUpdate: ElementSchema[] = [];
        if (targetIndex > currentPriorityIndex) {
          elementsToUpdate = sortedElements.slice(
            currentPriorityIndex + 1,
            targetIndex + 1,
          );
        } else if (targetIndex < currentPriorityIndex) {
          elementsToUpdate = sortedElements.slice(
            targetIndex,
            currentPriorityIndex,
          );
        }

        for (const elementToUpdate of elementsToUpdate) {
          const priority: number = utils.getPriority(elementToUpdate);

          utils.updatePriority(
            targetIndex > currentPriorityIndex ? priority - 1 : priority + 1,
            elementToUpdate,
          );
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
    element: ElementWithParentId,
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
    element: ElementWithParentId,
  ): Promise<ActionDifference> {
    const chainDiff: ActionDifference = {
      updatedElements: [],
    };
    if (await OrderedElementService.isOrdered(element)) {
      const utils = await OrderedElementUtils.create(element);
      const currentPriority: number = utils.getPriority();

      const parentElement = this.getParentElement(element.parentElementId!);

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

  private getParentElement(parentId: string): ElementSchema {
    return findElementById(
      this.chainElements,
      parentId,
    )?.element!;
  }
}
