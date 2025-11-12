import { DOMParser } from "@xmldom/xmldom";
import type { WsdlResource } from "./WsdlLoader";

export class WsdlDocumentRegistry {
    private readonly documents = new Map<string, Document>();
    private readonly entries: Array<{ uri: string; document: Document }> = [];

    constructor(resources: WsdlResource[]) {
        resources.forEach((resource) => {
            const parser = new DOMParser();
            const document = parser.parseFromString(resource.content, "text/xml");
            this.documents.set(resource.uri, document);
            this.entries.push({ uri: resource.uri, document });
        });
    }

    get(uri: string): Document | undefined {
        return this.documents.get(uri);
    }

    getAll(): Document[] {
        return this.entries.map((entry) => entry.document);
    }

    getAllEntries(): Array<{ uri: string; document: Document }> {
        return [...this.entries];
    }
}

