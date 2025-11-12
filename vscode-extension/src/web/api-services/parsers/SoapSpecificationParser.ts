import { WsdlParser } from "./soap/WsdlParser";
import type { WsdlParseResult } from "./soap/WsdlTypes";
import { WsdlLoader, WsdlResolver } from "./soap/WsdlLoader";
import { SoapSchemaGenerator } from "./soap/SoapSchemaGenerator";

const wsdlParser = new WsdlParser();

interface SoapParseOptions {
    fileName?: string;
    additionalDocuments?: Array<{ uri: string; content: string }>;
}

export class SoapSpecificationParser {

    static async parseWsdlContent(content: string, options?: SoapParseOptions): Promise<WsdlParseResult> {
        const mainUri = this.normalizePath(options?.fileName ?? "main.wsdl");
        const additionalDocuments = options?.additionalDocuments ?? [];
        const documentsMap = new Map<string, string>();
        additionalDocuments.forEach((document) => {
            const normalizedUri = this.normalizePath(document.uri);
            documentsMap.set(normalizedUri, document.content);
            const fileName = normalizedUri.split("/").pop();
            if (fileName) {
                documentsMap.set(fileName, document.content);
            }
            if (document.uri !== normalizedUri) {
                documentsMap.set(document.uri, document.content);
            }
        });

        const resolver: WsdlResolver = async (importUri, baseUri) => {
            const candidates = this.resolveImportCandidates(importUri, baseUri);
            for (const candidate of candidates) {
                const normalized = this.normalizePath(candidate);
                if (documentsMap.has(normalized)) {
                    return {
                        uri: normalized,
                        content: documentsMap.get(normalized)!,
                    };
                }
            }
            return null;
        };

        const loader = new WsdlLoader(resolver);
        const resources = await loader.load(mainUri, content);

        return wsdlParser.parse(resources, mainUri);
    }

    static createOperationsFromWsdl(wsdlData: WsdlParseResult, specificationId: string): any[] {
        const operations: any[] = [];
        const seen = new Set<string>();
        const schemaGenerator = new SoapSchemaGenerator(wsdlData);
        const schemaMap = schemaGenerator.buildOperationSchemas();

        wsdlData.operations.forEach((operationName) => {
            if (seen.has(operationName)) {
                return;
            }
            seen.add(operationName);

            const schemas = schemaMap.get(operationName);

            operations.push({
                id: `${specificationId}-${operationName}`,
                name: operationName,
                method: "POST",
                path: "",
                specification: {
                    operationId: operationName
                },
                requestSchema: schemas?.request ?? {},
                responseSchemas: schemas?.response ?? {}
            });
        });

        return operations;
    }

    static extractAddressFromWsdlData(wsdlData: WsdlParseResult): string | null {
        const endpoint = wsdlData.endpoints.find((item) => Boolean(item.address));
        return endpoint?.address ?? null;
    }

    private static normalizePath(value: string): string {
        return value
            .replace(/\\/g, "/")
            .split("/")
            .reduce<string[]>((segments, segment) => {
                if (!segment || segment === ".") {
                    return segments;
                }
                if (segment === "..") {
                    segments.pop();
                } else {
                    segments.push(segment);
                }
                return segments;
            }, [])
            .join("/");
    }

    private static resolveImportCandidates(importUri: string, baseUri: string): string[] {
        const candidates = new Set<string>();
        const normalizedImport = this.normalizePath(importUri);
        if (normalizedImport) {
            candidates.add(normalizedImport);
        }

        const baseDir = this.getDirectory(baseUri);
        if (baseDir) {
            const combined = this.normalizePath(`${baseDir}/${importUri}`);
            if (combined) {
                candidates.add(combined);
            }
        }

        const fileName = normalizedImport.split("/").pop();
        if (fileName) {
            candidates.add(fileName);
        }

        return Array.from(candidates);
    }

    private static getDirectory(uri: string): string {
        const normalized = this.normalizePath(uri);
        const lastSlash = normalized.lastIndexOf("/");
        return lastSlash >= 0 ? normalized.substring(0, lastSlash) : "";
    }
}
