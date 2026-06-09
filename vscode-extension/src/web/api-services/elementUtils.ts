import { Element as ElementSchema } from "@netcracker/qip-schemas";

export function getType(element: ElementSchema): string {
  return element.type as unknown as string;
}
