import { Element as ElementSchema } from "@netcracker/qip-schemas";

export class ResourceFileNames {
  constructor(
    private readonly genericFilename?: string,
    private readonly beforeFilename?: string,
    private readonly afterFilenames: Map<string, string> = new Map(),
  ) {}

  static fromElement(element: ElementSchema): ResourceFileNames {
    const genericFilename = (element.properties as any)?.propertiesFilename as string | undefined;
    const beforeFilename = (element.properties as any)?.before?.propertiesFilename as string | undefined;
    const afterFilenames = new Map<string, string>();
    for (const block of ((element.properties as any)?.after as any[]) ?? []) {
      if (block?.propertiesFilename) {
        afterFilenames.set(`${block.type}:${block.id ?? block.code ?? ""}`, block.propertiesFilename as string);
      }
    }
    return new ResourceFileNames(genericFilename, beforeFilename, afterFilenames);
  }

  static empty(): ResourceFileNames {
    return new ResourceFileNames(undefined, undefined, new Map());
  }

  getGeneric(): string | undefined {
    return this.genericFilename;
  }

  getBefore(): string | undefined {
    return this.beforeFilename;
  }

  getAfter(type: string, idOrCode: string): string | undefined {
    return this.afterFilenames.get(`${type}:${idOrCode}`);
  }
}
