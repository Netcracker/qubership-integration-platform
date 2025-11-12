import type {
    WsdlMessage,
    WsdlMessagePart,
    WsdlParseResult,
    WsdlQualifiedName,
    WsdlSchemaEntry
} from "./WsdlTypes";

interface SoapOperationSchemas {
    request?: Record<string, any>;
    response?: Record<string, any>;
}

interface SchemaBuildContext {
    definitions: Record<string, any>;
    definitionRefs: Map<string, string>;
}

interface SequenceElementSchema {
    propertyName: string;
    schema: any;
    required: boolean;
}

const XML_SCHEMA_NS = "http://www.w3.org/2001/XMLSchema";

export class SoapSchemaGenerator {
    private readonly schemaStore: XmlSchemaStore;

    constructor(private readonly wsdlData: WsdlParseResult) {
        this.schemaStore = new XmlSchemaStore(wsdlData.schemas);
    }

    buildOperationSchemas(): Map<string, SoapOperationSchemas> {
        const result = new Map<string, SoapOperationSchemas>();
        Object.entries(this.wsdlData.operationDetails).forEach(([operationName, details]) => {
            const requestSchema = this.buildMessageContent(details.input);
            const responseSchema = this.buildMessageContent(details.output);

            if (!requestSchema && !responseSchema) {
                return;
            }

            const operationSchemas: SoapOperationSchemas = {};
            if (requestSchema) {
                operationSchemas.request = {
                    "application/xml": requestSchema
                };
            }
            if (responseSchema) {
                operationSchemas.response = {
                    "200": {
                        "application/xml": responseSchema
                    }
                };
            }

            result.set(operationName, operationSchemas);
        });
        return result;
    }

    private buildMessageContent(messageRef?: WsdlQualifiedName): Record<string, any> | undefined {
        const message = this.resolveMessage(messageRef);
        if (!message || message.parts.length === 0) {
            return undefined;
        }

        const context = this.createSchemaContext();
        const properties: Record<string, any> = {};
        const required: Set<string> = new Set<string>();

        message.parts.forEach((part, index) => {
            const partSchema = this.buildSchemaForMessagePart(part, context);
            if (!partSchema) {
                return;
            }
            const propertyName = partSchema.propertyName || part.name || part.element?.name || `part${index + 1}`;
            properties[propertyName] = partSchema.schema;
            if (partSchema.required) {
                required.add(propertyName);
            }
        });

        if (Object.keys(properties).length === 0) {
            return undefined;
        }

        const schema: Record<string, any> = {
            type: "object",
            properties
        };
        if (required.size > 0) {
            schema.required = Array.from(required);
        }
        if (Object.keys(context.definitions).length > 0) {
            schema.definitions = context.definitions;
        }
        return schema;
    }

    private resolveMessage(messageRef?: WsdlQualifiedName): WsdlMessage | undefined {
        if (!messageRef) {
            return undefined;
        }
        const key = SoapSchemaGenerator.buildQualifiedNameKey(messageRef);
        if (this.wsdlData.messages[key]) {
            return this.wsdlData.messages[key];
        }

        // Fallback to name-only lookup
        const nameOnlyKey = SoapSchemaGenerator.buildQualifiedNameKey({ namespace: undefined, name: messageRef.name });
        if (this.wsdlData.messages[nameOnlyKey]) {
            return this.wsdlData.messages[nameOnlyKey];
        }

        return Object.values(this.wsdlData.messages).find((message) => message.name === messageRef.name);
    }

    private buildSchemaForMessagePart(part: WsdlMessagePart, context: SchemaBuildContext): SequenceElementSchema | undefined {
        if (part.element) {
            const schema = this.buildSchemaFromElement(part.element, context);
            return {
                propertyName: part.element.name,
                schema,
                required: true
            };
        }
        if (part.type) {
            const schema = this.buildSchemaForTypeReference(part.type, context);
            return {
                propertyName: part.name || part.type.name,
                schema,
                required: true
            };
        }
        if (part.name) {
            return {
                propertyName: part.name,
                schema: { type: "string" },
                required: true
            };
        }
        return undefined;
    }

    private buildSchemaFromElement(qName: WsdlQualifiedName, context: SchemaBuildContext): any {
        const key = this.makeElementRefKey(qName);
        const existingDef = context.definitionRefs.get(key);
        if (existingDef) {
            return { $ref: `#/definitions/${existingDef}` };
        }

        const element = this.schemaStore.getElement(qName);
        if (!element) {
            // Fallback to primitive mapping
            return this.mapPrimitiveType(qName);
        }

        const definitionName = this.generateDefinitionName("Element", qName, context);
        context.definitionRefs.set(key, definitionName);
        const schemaBody = this.buildElementSchemaBody(element, context);
        context.definitions[definitionName] = schemaBody;
        return { $ref: `#/definitions/${definitionName}` };
    }

    private buildElementSchemaBody(element: Element, context: SchemaBuildContext): any {
        const typeAttr = element.getAttribute("type");
        if (typeAttr) {
            const resolved = this.resolveQualifiedName(element, typeAttr);
            if (resolved) {
                return this.buildSchemaForTypeReference(resolved, context);
            }
        }

        const complexType = this.findFirstChildElement(element, "complexType");
        if (complexType) {
            return this.buildComplexTypeSchema(complexType, context);
        }

        const simpleType = this.findFirstChildElement(element, "simpleType");
        if (simpleType) {
            return this.buildSimpleTypeSchema(simpleType, context);
        }

        return { type: "string" };
    }

    private buildSchemaForTypeReference(qName: WsdlQualifiedName, context: SchemaBuildContext): any {
        if (qName.namespace === XML_SCHEMA_NS || !qName.namespace) {
            const primitive = this.mapPrimitiveType(qName);
            if (primitive) {
                return primitive;
            }
        }

        const key = this.makeTypeRefKey(qName);
        const existingDef = context.definitionRefs.get(key);
        if (existingDef) {
            return { $ref: `#/definitions/${existingDef}` };
        }

        const typeDefinition = this.schemaStore.getType(qName);
        if (!typeDefinition) {
            return { type: "string" };
        }

        const definitionName = this.generateDefinitionName("Type", qName, context);
        context.definitionRefs.set(key, definitionName);

        let schemaBody: any;
        if (typeDefinition.kind === "complex") {
            schemaBody = this.buildComplexTypeSchema(typeDefinition.element, context);
        } else {
            schemaBody = this.buildSimpleTypeSchema(typeDefinition.element, context);
        }

        context.definitions[definitionName] = schemaBody;

        return { $ref: `#/definitions/${definitionName}` };
    }

    private buildComplexTypeSchema(complexType: Element, context: SchemaBuildContext): any {
        const sequence = this.findFirstChildElement(complexType, "sequence");
        if (sequence) {
            return this.buildSequenceSchema(sequence, context);
        }

        const all = this.findFirstChildElement(complexType, "all");
        if (all) {
            return this.buildSequenceSchema(all, context);
        }

        const choice = this.findFirstChildElement(complexType, "choice");
        if (choice) {
            const options: any[] = [];
            this.forEachChildElement(choice, (child) => {
                if (child.namespaceURI === XML_SCHEMA_NS && child.localName === "element") {
                    const elementQName = this.resolveElementQualifiedName(child);
                    const childSchema = elementQName
                        ? this.buildSchemaFromElement(elementQName, context)
                        : this.buildAnonymousElementSchema(child, context);
                    if (childSchema) {
                        options.push(childSchema);
                    }
                }
            });
            if (options.length > 0) {
                return {
                    anyOf: options
                };
            }
        }

        const simpleContent = this.findFirstChildElement(complexType, "simpleContent");
        if (simpleContent) {
            const extension = this.findFirstChildElement(simpleContent, "extension");
            if (extension) {
                const baseAttr = extension.getAttribute("base");
                const baseType = baseAttr ? this.resolveQualifiedName(extension, baseAttr) : undefined;
                if (baseType) {
                    return this.buildSchemaForTypeReference(baseType, context);
                }
            }
        }

        const complexContent = this.findFirstChildElement(complexType, "complexContent");
        if (complexContent) {
            const extension = this.findFirstChildElement(complexContent, "extension");
            if (extension) {
                const baseAttr = extension.getAttribute("base");
                const baseType = baseAttr ? this.resolveQualifiedName(extension, baseAttr) : undefined;
                let baseSchema: any = baseType ? this.buildSchemaForTypeReference(baseType, context) : { type: "object" };
                const sequenceChild = this.findFirstChildElement(extension, "sequence");
                if (sequenceChild) {
                    const sequenceSchema = this.buildSequenceSchema(sequenceChild, context);
                    baseSchema = this.mergeObjectSchemas(baseSchema, sequenceSchema);
                }
                return baseSchema;
            }
        }

        return {
            type: "object"
        };
    }

    private buildSequenceSchema(sequence: Element, context: SchemaBuildContext): any {
        const properties: Record<string, any> = {};
        const required: Set<string> = new Set<string>();
        let index = 0;

        this.forEachChildElement(sequence, (child) => {
            if (child.namespaceURI !== XML_SCHEMA_NS || child.localName !== "element") {
                return;
            }
            index += 1;
            const elementSchema = this.buildSequenceElementSchema(child, context, index);
            if (!elementSchema) {
                return;
            }
            properties[elementSchema.propertyName] = elementSchema.schema;
            if (elementSchema.required) {
                required.add(elementSchema.propertyName);
            }
        });

        const schema: any = {
            type: "object",
            properties
        };
        if (required.size > 0) {
            schema.required = Array.from(required);
        }
        return schema;
    }

    private buildSequenceElementSchema(element: Element, context: SchemaBuildContext, index: number): SequenceElementSchema | undefined {
        const minOccursAttr = element.getAttribute("minOccurs");
        const maxOccursAttr = element.getAttribute("maxOccurs");
        const minOccurs = minOccursAttr ? parseInt(minOccursAttr, 10) : 1;
        const isArray = maxOccursAttr === "unbounded" || (maxOccursAttr ? parseInt(maxOccursAttr, 10) > 1 : false);

        const elementRef = element.getAttribute("ref");
        let propertyName = element.getAttribute("name") || undefined;
        let schema: any | undefined;

        if (elementRef) {
            const qName = this.resolveQualifiedName(element, elementRef);
            propertyName = propertyName || qName?.name || `field${index}`;
            if (qName) {
                schema = this.buildSchemaFromElement(qName, context);
            }
        } else {
            propertyName = propertyName || `field${index}`;
            schema = this.buildAnonymousElementSchema(element, context);
        }

        if (!schema) {
            return undefined;
        }

        if (isArray) {
            schema = {
                type: "array",
                items: schema
            };
        }

        return {
            propertyName,
            schema,
            required: minOccurs > 0
        };
    }

    private buildAnonymousElementSchema(element: Element, context: SchemaBuildContext): any {
        const typeAttr = element.getAttribute("type");
        if (typeAttr) {
            const resolvedType = this.resolveQualifiedName(element, typeAttr);
            if (resolvedType) {
                return this.buildSchemaForTypeReference(resolvedType, context);
            }
        }
        const complexType = this.findFirstChildElement(element, "complexType");
        if (complexType) {
            return this.buildComplexTypeSchema(complexType, context);
        }
        const simpleType = this.findFirstChildElement(element, "simpleType");
        if (simpleType) {
            return this.buildSimpleTypeSchema(simpleType, context);
        }
        return { type: "string" };
    }

    private buildSimpleTypeSchema(simpleType: Element, context: SchemaBuildContext): any {
        const restriction = this.findFirstChildElement(simpleType, "restriction");
        if (restriction) {
            const baseAttr = restriction.getAttribute("base");
            const baseType = baseAttr ? this.resolveQualifiedName(restriction, baseAttr) : undefined;
            const schema = baseType ? this.mapPrimitiveType(baseType) : { type: "string" };

            this.forEachChildElement(restriction, (facet) => {
                if (facet.namespaceURI !== XML_SCHEMA_NS) {
                    return;
                }
                const value = facet.getAttribute("value");
                if (value === null) {
                    return;
                }
                switch (facet.localName) {
                    case "enumeration":
                        if (value !== undefined) {
                            (schema.enum ??= []).push(value);
                        }
                        break;
                    case "pattern":
                        schema.pattern = value;
                        break;
                    case "minLength":
                        schema.minLength = Number(value);
                        break;
                    case "maxLength":
                        schema.maxLength = Number(value);
                        break;
                    case "minInclusive":
                        schema.minimum = Number(value);
                        break;
                    case "maxInclusive":
                        schema.maximum = Number(value);
                        break;
                    case "minExclusive":
                        schema.exclusiveMinimum = Number(value);
                        break;
                    case "maxExclusive":
                        schema.exclusiveMaximum = Number(value);
                        break;
                    default:
                        break;
                }
            });

            return schema;
        }

        const listElement = this.findFirstChildElement(simpleType, "list");
        if (listElement) {
            const itemTypeAttr = listElement.getAttribute("itemType");
            const itemType = itemTypeAttr ? this.resolveQualifiedName(listElement, itemTypeAttr) : undefined;
            const itemSchema = itemType ? this.buildSchemaForTypeReference(itemType, context) : { type: "string" };
            return {
                type: "array",
                items: itemSchema
            };
        }

        return { type: "string" };
    }

    private mergeObjectSchemas(baseSchema: any, extensionSchema: any): any {
        if (!baseSchema || baseSchema.type !== "object" || !extensionSchema || extensionSchema.type !== "object") {
            return extensionSchema || baseSchema;
        }
        const merged: any = {
            type: "object",
            properties: { ...(baseSchema.properties ?? {}), ...(extensionSchema.properties ?? {}) }
        };
        const required = new Set<string>();
        (baseSchema.required ?? []).forEach((item: string) => required.add(item));
        (extensionSchema.required ?? []).forEach((item: string) => required.add(item));
        if (required.size > 0) {
            merged.required = Array.from(required);
        }
        if (baseSchema.definitions || extensionSchema.definitions) {
            merged.definitions = {
                ...(baseSchema.definitions ?? {}),
                ...(extensionSchema.definitions ?? {})
            };
        }
        return merged;
    }

    private mapPrimitiveType(qName?: WsdlQualifiedName): any {
        if (!qName) {
            return { type: "string" };
        }
        const namespace = qName.namespace ?? XML_SCHEMA_NS;
        const name = qName.name;
        if (namespace !== XML_SCHEMA_NS) {
            return { type: "string" };
        }
        switch (name) {
            case "string":
            case "normalizedString":
            case "token":
            case "language":
            case "Name":
            case "NCName":
            case "ID":
            case "IDREF":
            case "ENTITY":
            case "QName":
                return { type: "string" };
            case "boolean":
                return { type: "boolean" };
            case "decimal":
            case "float":
            case "double":
                return { type: "number" };
            case "byte":
            case "short":
            case "int":
            case "integer":
            case "long":
            case "nonNegativeInteger":
            case "positiveInteger":
            case "negativeInteger":
            case "nonPositiveInteger":
            case "unsignedByte":
            case "unsignedShort":
            case "unsignedInt":
            case "unsignedLong":
                return { type: "integer" };
            case "date":
                return { type: "string", format: "date" };
            case "dateTime":
                return { type: "string", format: "date-time" };
            case "time":
                return { type: "string", format: "time" };
            case "base64Binary":
                return { type: "string", contentEncoding: "base64" };
            case "anyURI":
                return { type: "string", format: "uri" };
            default:
                return { type: "string" };
        }
    }

    private createSchemaContext(): SchemaBuildContext {
        return {
            definitions: {},
            definitionRefs: new Map<string, string>()
        };
    }

    private resolveElementQualifiedName(element: Element): WsdlQualifiedName | undefined {
        const nameAttr = element.getAttribute("name");
        const schemaNamespace = this.getSchemaTargetNamespace(element);
        if (nameAttr) {
            return {
                namespace: schemaNamespace,
                name: nameAttr
            };
        }
        const refAttr = element.getAttribute("ref");
        return refAttr ? this.resolveQualifiedName(element, refAttr) : undefined;
    }

    private resolveQualifiedName(node: Element, value: string): WsdlQualifiedName | undefined {
        const trimmed = value?.trim();
        if (!trimmed) {
            return undefined;
        }
        const parts = trimmed.split(":");
        let namespace: string | null;
        let localName: string;
        if (parts.length > 1) {
            const prefix = parts[0];
            localName = parts[1];
            namespace = node.lookupNamespaceURI(prefix);
        } else {
            localName = parts[0];
            namespace = node.lookupNamespaceURI(null);
        }
        if (!localName) {
            return undefined;
        }
        if (!namespace) {
            namespace = this.getSchemaTargetNamespace(node) || null;
        }
        return {
            namespace: namespace || undefined,
            name: localName,
            raw: trimmed
        };
    }

    private getSchemaTargetNamespace(node: Element): string | undefined {
        let current: Element | null = node;
        while (current) {
            if (current.namespaceURI === XML_SCHEMA_NS && current.localName === "schema") {
                const ns = current.getAttribute("targetNamespace");
                return ns || undefined;
            }
            current = current.parentNode instanceof Element ? (current.parentNode as Element) : null;
        }
        return undefined;
    }

    private generateDefinitionName(prefix: string, qName: WsdlQualifiedName, context: SchemaBuildContext): string {
        const ns = qName.namespace ? qName.namespace.replace(/[^A-Za-z0-9]+/g, "_") : "default";
        let baseName = `${prefix}_${ns}_${qName.name}`.replace(/[^A-Za-z0-9_]+/g, "_");
        if (!baseName) {
            baseName = `${prefix}_definition`;
        }
        let candidate = baseName;
        let index = 1;
        while (context.definitions[candidate]) {
            candidate = `${baseName}_${index++}`;
        }
        return candidate;
    }

    private makeElementRefKey(qName: WsdlQualifiedName): string {
        return `E|${XmlSchemaStore.buildKey(qName)}`;
    }

    private makeTypeRefKey(qName: WsdlQualifiedName): string {
        return `T|${XmlSchemaStore.buildKey(qName)}`;
    }

    private findFirstChildElement(element: Element, localName: string): Element | undefined {
        let current = element.firstChild;
        while (current) {
            if (current.nodeType === 1) {
                const child = current as Element;
                if (child.namespaceURI === XML_SCHEMA_NS && child.localName === localName) {
                    return child;
                }
            }
            current = current.nextSibling;
        }
        return undefined;
    }

    private forEachChildElement(element: Element, visitor: (element: Element) => void) {
        let current = element.firstChild;
        while (current) {
            if (current.nodeType === 1) {
                visitor(current as Element);
            }
            current = current.nextSibling;
        }
    }

    private static buildQualifiedNameKey(qName?: WsdlQualifiedName): string {
        if (!qName) {
            return "";
        }
        return `${qName.namespace ?? ""}#${qName.name}`;
    }
}

class XmlSchemaStore {
    private readonly elementMap = new Map<string, Element>();
    private readonly complexTypeMap = new Map<string, Element>();
    private readonly simpleTypeMap = new Map<string, Element>();

    constructor(entries: WsdlSchemaEntry[]) {
        entries.forEach((entry) => this.indexSchema(entry));
    }

    getElement(qName: WsdlQualifiedName): Element | undefined {
        const direct = this.elementMap.get(XmlSchemaStore.buildKey(qName));
        if (direct) {
            return direct;
        }
        // fallback by local name
        for (const [key, element] of this.elementMap.entries()) {
            if (key.endsWith(`#${qName.name}`)) {
                return element;
            }
        }
        return undefined;
    }

    getType(qName: WsdlQualifiedName): { kind: "complex" | "simple"; element: Element } | undefined {
        const complex = this.complexTypeMap.get(XmlSchemaStore.buildKey(qName));
        if (complex) {
            return { kind: "complex", element: complex };
        }
        const simple = this.simpleTypeMap.get(XmlSchemaStore.buildKey(qName));
        if (simple) {
            return { kind: "simple", element: simple };
        }
        // fallback search by local name
        for (const [key, element] of this.complexTypeMap.entries()) {
            if (key.endsWith(`#${qName.name}`)) {
                return { kind: "complex", element };
            }
        }
        for (const [key, element] of this.simpleTypeMap.entries()) {
            if (key.endsWith(`#${qName.name}`)) {
                return { kind: "simple", element };
            }
        }
        return undefined;
    }

    static buildKey(qName: WsdlQualifiedName): string {
        return `${qName.namespace ?? ""}#${qName.name}`;
    }

    private indexSchema(entry: WsdlSchemaEntry): void {
        const schemaElement = entry.element;
        const targetNamespace = schemaElement.getAttribute("targetNamespace") || entry.targetNamespace || undefined;

        this.forEachChildElement(schemaElement, (child) => {
            if (child.namespaceURI !== XML_SCHEMA_NS) {
                return;
            }
            const name = child.getAttribute("name");
            if (!name) {
                return;
            }
            const key = XmlSchemaStore.buildKey({ namespace: targetNamespace, name });
            switch (child.localName) {
                case "element":
                    if (!this.elementMap.has(key)) {
                        this.elementMap.set(key, child);
                    }
                    break;
                case "complexType":
                    if (!this.complexTypeMap.has(key)) {
                        this.complexTypeMap.set(key, child);
                    }
                    break;
                case "simpleType":
                    if (!this.simpleTypeMap.has(key)) {
                        this.simpleTypeMap.set(key, child);
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private forEachChildElement(element: Element, visitor: (element: Element) => void) {
        let current = element.firstChild;
        while (current) {
            if (current.nodeType === 1) {
                visitor(current as Element);
            }
            current = current.nextSibling;
        }
    }
}


