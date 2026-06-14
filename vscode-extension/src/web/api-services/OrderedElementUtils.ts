import { Element as ElementSchema } from "@netcracker/qip-schemas";
import { LibraryElement } from "@netcracker/qip-ui";
import { getLibraryElementByType } from "../response/chainApiRead";
import { getType } from "./elementUtils";

export class OrderedElementUtils {
  private constructor(
    public element: ElementSchema,
    private readonly libraryElement: LibraryElement,
  ) {}

  static async create(element: ElementSchema): Promise<OrderedElementUtils> {
    return new OrderedElementUtils(
      element,
      await getLibraryElementByType(getType(element)),
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

  getIndexForNewPriority(sortedElements: ElementSchema[], newPriority: number): number {
    return sortedElements.findIndex((element) =>
      this.getPriority(element) === newPriority,
    );
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
