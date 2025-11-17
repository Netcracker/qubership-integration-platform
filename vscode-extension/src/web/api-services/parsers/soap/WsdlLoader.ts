export interface WsdlResource {
    uri: string;
    content: string;
}

export type WsdlResolver = (uri: string, baseUri: string) => Promise<WsdlResource | null>;

export class WsdlLoader {
    constructor(private readonly resolver: WsdlResolver) {}

    async load(mainUri: string, content: string): Promise<WsdlResource[]> {
        const visited = new Map<string, WsdlResource>();
        await this.collectRecursive(mainUri, content, visited);
        return Array.from(visited.values());
    }

    private async collectRecursive(uri: string, content: string, visited: Map<string, WsdlResource>): Promise<void> {
        if (visited.has(uri)) {
            return;
        }

        const resource: WsdlResource = { uri, content };
        visited.set(uri, resource);

        const imports = this.extractImports(content);

        for (const importUri of imports) {
            if (visited.has(importUri)) {
                continue;
            }

            const resolved = await this.resolver(importUri, uri);
            if (!resolved) {
                console.warn('[WsdlLoader] Unable to resolve import', importUri, 'from', uri);
                continue;
            }

            await this.collectRecursive(resolved.uri, resolved.content, visited);
        }
    }

    private extractImports(content: string): string[] {
        const regex = /<(?:[a-zA-Z0-9_]+:)?(?:import|include)[^>]*(?:schemaLocation|location)\s*=\s*(?:"([^"]+)"|'([^']+)')[^>]*>/gi;
        const imports: string[] = [];
        let match: RegExpExecArray | null;

        while ((match = regex.exec(content)) !== null) {
            const location = match[1] ?? match[2];
            if (location) {
                imports.push(location);
            }
        }

        return imports;
    }
}

