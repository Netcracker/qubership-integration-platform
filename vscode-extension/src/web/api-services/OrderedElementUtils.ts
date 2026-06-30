import { Element as ElementSchema } from "@netcracker/qip-schemas";
import { LibraryElement } from "@netcracker/qip-ui";
import { getLibraryElementByType } from "../response/chainApiRead";
import { ElementWithParentId, getType } from "./elementUtils";

export class OrderedElementUtils {
  private constructor(
    public element: ElementSchema,
    public parentElementId: string,
    private readonly libraryElement: LibraryElement,
  ) {}

  static async create(elementWithParentId: ElementWithParentId): Promise<OrderedElementUtils> {
    return new OrderedElementUtils(
      elementWithParentId.element,
      elementWithParentId.parentElementId!,
      await getLibraryElementByType(getType(elementWithParentId.element)),
    );
  }

  getPriority(element?: ElementSchema): number {
    return ((element ?? this.element).properties as Record<string, unknown>)[
      this.getPriorityProperty()
    ] as number;
  }

  getPriorityOrUndefined(
    properties: Record<string, unknown>,
  ): number | undefined {
    return properties[this.getPriorityProperty()] as number;
  }

  updatePriority(priority: number, element?: ElementSchema) {
    ((element ?? this.element).properties as Record<string, unknown>)[
      this.getPriorityProperty()
    ] = priority;
  }

  getIndex(sortedElements: ElementSchema[], priority?: number): number {
    return sortedElements.findIndex((element) =>
      priority === undefined
        ? element.id === this.element.id
        : this.getPriority(element) === priority,
    );
  }

  private getIndexByPredicate(
    sortedElements: ElementSchema[],
    predicate: (currentPriority: number) => boolean,
  ): number {
    return sortedElements.findIndex((element) =>
      predicate(this.getPriority(element)),
    );
  }

  getIndexToInsert(sortedElements: ElementSchema[], priority: number): number {
    let targetIndex = this.getIndexByPredicate(
      sortedElements,
      (currentPriority) => currentPriority === priority,
    );

    if (targetIndex === -1) {
      targetIndex = this.getIndexByPredicate(
        sortedElements,
        (currentPriority) => currentPriority > priority,
      );
    }

    return targetIndex === -1 ? sortedElements.length - 1 : targetIndex;
  }

  extractOtherOrderedElements(parentElement: ElementSchema): ElementSchema[] {
    return this.extractOrderedElements(parentElement).filter(
      (child) => this.element.id !== child.id,
    );
  }

  extractSortedOrderedElements(parentElement: ElementSchema): ElementSchema[] {
    return this.extractOrderedElements(parentElement).sort((left, right) => {
      const leftPriority = this.getPriority(left);
      const rightPriority = this.getPriority(right);

      if (leftPriority === rightPriority) {
        return 0;
      } else if (leftPriority > rightPriority) {
        return 1;
      } else {
        return -1;
      }
    });
  }

  private extractOrderedElements(
    parentElement: ElementSchema,
  ): ElementSchema[] {
    return (parentElement.children as ElementSchema[]).filter(
      (child) => this.element.type === child.type,
    );
  }

  private getPriorityProperty(): string {
    return this.libraryElement.priorityProperty ?? "priority";
  }
}
