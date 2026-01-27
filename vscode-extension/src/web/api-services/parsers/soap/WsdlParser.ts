import { WsdlDocumentRegistry } from "./WsdlDocumentRegistry";
import type {
  WsdlEndpoint,
  WsdlMessage,
  WsdlOperationDetails,
  WsdlParseResult,
  WsdlQualifiedName,
  WsdlSchemaEntry,
  WsdlVersion,
} from "./WsdlTypes";
import type { WsdlResource } from "./WsdlLoader";

const WSDL_11_NS = "http://schemas.xmlsoap.org/wsdl/";
const WSDL_20_NS = "http://www.w3.org/ns/wsdl";
const SOAP_11_NS = "http://schemas.xmlsoap.org/wsdl/soap/";
const SOAP_12_NS = "http://schemas.xmlsoap.org/wsdl/soap12/";
const XML_SCHEMA_NS = "http://www.w3.org/2001/XMLSchema";

export class WsdlParser {
  parse(resources: WsdlResource[], mainUri: string): WsdlParseResult {
    const registry = new WsdlDocumentRegistry(resources);
    const entries = registry.getAllEntries();
    if (entries.length === 0) {
      throw new Error("Unsupported WSDL: document has no root element");
    }

    const mainDocument = registry.get(mainUri) ?? entries[0].document;
    const mainRoot = mainDocument.documentElement;
    if (!mainRoot) {
      throw new Error("Unsupported WSDL: document has no root element");
    }

    const version = this.detectVersion(mainRoot.namespaceURI ?? null);
    const targetNamespace =
      mainRoot.getAttribute("targetNamespace") || undefined;

    const operations = new Set<string>();
    const endpoints: WsdlEndpoint[] = [];
    const messages = new Map<string, WsdlMessage>();
    const operationDetails = new Map<string, WsdlOperationDetails>();
    const schemas: WsdlSchemaEntry[] = [];

    entries.forEach(({ document, uri }) => {
      const root = document.documentElement;
      if (!root) {
        return;
      }

      if (root.namespaceURI === WSDL_11_NS) {
        this.collectOperationsV11(root, operations);
        this.collectEndpointsV11(root, endpoints);
        this.collectMessagesV11(root, messages);
        this.collectOperationMessagesV11(root, operationDetails);
      } else if (root.namespaceURI === WSDL_20_NS) {
        this.collectOperationsV20(root, operations);
        this.collectEndpointsV20(root, endpoints);
        this.collectOperationMessagesV20(root, operationDetails);
      }
      this.collectSchemas(root, uri, schemas);
    });

    const uniqueEndpoints = this.deduplicateEndpoints(endpoints);
    const serviceNames = Array.from(
      new Set(
        uniqueEndpoints.map((endpoint) => endpoint.serviceName).filter(Boolean),
      ),
    ) as string[];
    const operationArray = Array.from(operations);

    const operationRecord: Record<string, WsdlOperationDetails> = {};
    operationArray.forEach((operationName) => {
      const details = operationDetails.get(operationName) ?? {
        name: operationName,
      };
      operationRecord[operationName] = details;
    });

    const messageRecord: Record<string, WsdlMessage> = {};
    messages.forEach((value, key) => {
      messageRecord[key] = value;
    });

    return {
      type: "WSDL",
      version,
      targetNamespace,
      operations: operationArray,
      endpoints: uniqueEndpoints,
      serviceNames,
      operationDetails: operationRecord,
      messages: messageRecord,
      schemas,
    };
  }

  private detectVersion(namespaceUri: string | null): WsdlVersion {
    if (namespaceUri === WSDL_20_NS) {
      return "2.0";
    }
    return "1.1";
  }

  private collectOperationsV11(root: Element, operations: Set<string>): void {
    this.traverseElements(root, (element) => {
      if (
        element.namespaceURI === WSDL_11_NS &&
        element.localName === "operation"
      ) {
        const parent = element.parentNode;
        if (
          parent &&
          (parent as Element).namespaceURI === WSDL_11_NS &&
          (parent as Element).localName === "binding"
        ) {
          const name = element.getAttribute("name");
          if (name) {
            operations.add(name);
          }
        }
      }
    });
  }

  private collectOperationsV20(root: Element, operations: Set<string>): void {
    this.traverseElements(root, (element) => {
      if (
        element.namespaceURI === WSDL_20_NS &&
        element.localName === "binding"
      ) {
        this.forEachChildElement(element, (child) => {
          if (
            child.namespaceURI === WSDL_20_NS &&
            child.localName === "operation"
          ) {
            const ref = child.getAttribute("ref");
            if (ref) {
              const operationName = this.extractLocalName(ref);
              if (operationName) {
                operations.add(operationName);
              }
            }
          }
        });
      }
    });
  }

  private collectEndpointsV11(root: Element, endpoints: WsdlEndpoint[]): void {
    this.traverseElements(root, (element) => {
      if (
        element.namespaceURI === WSDL_11_NS &&
        element.localName === "service"
      ) {
        const serviceName = element.getAttribute("name") || undefined;

        this.forEachChildElement(element, (portElement) => {
          if (
            portElement.namespaceURI === WSDL_11_NS &&
            portElement.localName === "port"
          ) {
            const endpointName = portElement.getAttribute("name") || undefined;
            const addressElement = this.findChildAddress(portElement);
            const address =
              addressElement?.getAttribute("location") || undefined;

            if (address) {
              endpoints.push({
                serviceName,
                endpointName,
                address,
              });
            }
          }
        });
      }
    });
  }

  private collectEndpointsV20(root: Element, endpoints: WsdlEndpoint[]): void {
    this.traverseElements(root, (element) => {
      if (
        element.namespaceURI === WSDL_20_NS &&
        element.localName === "service"
      ) {
        const serviceName = element.getAttribute("name") || undefined;

        this.forEachChildElement(element, (endpointElement) => {
          if (
            endpointElement.namespaceURI === WSDL_20_NS &&
            endpointElement.localName === "endpoint"
          ) {
            const endpointName =
              endpointElement.getAttribute("name") || undefined;
            const address =
              endpointElement.getAttribute("address") || undefined;

            if (address) {
              endpoints.push({
                serviceName,
                endpointName,
                address,
              });
            }
          }
        });
      }
    });
  }

  private collectMessagesV11(
    root: Element,
    messages: Map<string, WsdlMessage>,
  ): void {
    if (root.namespaceURI !== WSDL_11_NS) {
      return;
    }
    const definitionsTargetNamespace =
      root.getAttribute("targetNamespace") || undefined;

    this.forEachChildElement(root, (element) => {
      if (
        element.namespaceURI === WSDL_11_NS &&
        element.localName === "message"
      ) {
        const messageName = element.getAttribute("name");
        if (!messageName) {
          return;
        }

        const key = this.buildQualifiedNameKey({
          namespace: definitionsTargetNamespace,
          name: messageName,
        });
        if (messages.has(key)) {
          return;
        }

        const parts: WsdlMessage["parts"] = [];
        this.forEachChildElement(element, (partElement) => {
          if (
            partElement.namespaceURI === WSDL_11_NS &&
            partElement.localName === "part"
          ) {
            const part: WsdlMessage["parts"][number] = {
              name: partElement.getAttribute("name") || undefined,
            };
            const elementAttr = partElement.getAttribute("element");
            const typeAttr = partElement.getAttribute("type");
            if (elementAttr) {
              part.element = this.resolveQualifiedName(
                partElement,
                elementAttr,
              );
            }
            if (typeAttr) {
              part.type = this.resolveQualifiedName(partElement, typeAttr);
            }
            parts.push(part);
          }
        });

        messages.set(key, {
          name: messageName,
          namespace: definitionsTargetNamespace,
          parts,
        });
      }
    });
  }

  private collectOperationMessagesV11(
    root: Element,
    operationDetails: Map<string, WsdlOperationDetails>,
  ): void {
    this.forEachChildElement(root, (element) => {
      if (
        element.namespaceURI === WSDL_11_NS &&
        element.localName === "portType"
      ) {
        this.forEachChildElement(element, (operationElement) => {
          if (
            operationElement.namespaceURI === WSDL_11_NS &&
            operationElement.localName === "operation"
          ) {
            const operationName = operationElement.getAttribute("name");
            if (!operationName) {
              return;
            }
            const existing = operationDetails.get(operationName) ?? {
              name: operationName,
            };
            const inputElement = this.findFirstChildElement(
              operationElement,
              WSDL_11_NS,
              "input",
            );
            const outputElement = this.findFirstChildElement(
              operationElement,
              WSDL_11_NS,
              "output",
            );

            if (inputElement) {
              existing.input = this.resolveQualifiedName(
                inputElement,
                inputElement.getAttribute("message"),
              );
            }
            if (outputElement) {
              existing.output = this.resolveQualifiedName(
                outputElement,
                outputElement.getAttribute("message"),
              );
            }

            operationDetails.set(operationName, existing);
          }
        });
      }
    });
  }

  private collectOperationMessagesV20(
    root: Element,
    operationDetails: Map<string, WsdlOperationDetails>,
  ): void {
    this.traverseElements(root, (element) => {
      if (
        element.namespaceURI === WSDL_20_NS &&
        element.localName === "interface"
      ) {
        this.forEachChildElement(element, (operationElement) => {
          if (
            operationElement.namespaceURI === WSDL_20_NS &&
            operationElement.localName === "operation"
          ) {
            const operationName = operationElement.getAttribute("name");
            if (!operationName) {
              return;
            }
            const existing = operationDetails.get(operationName) ?? {
              name: operationName,
            };
            this.forEachChildElement(operationElement, (child) => {
              if (child.namespaceURI !== WSDL_20_NS) {
                return;
              }
              if (child.localName === "input") {
                existing.input = this.resolveQualifiedName(
                  child,
                  child.getAttribute("element"),
                );
              } else if (child.localName === "output") {
                existing.output = this.resolveQualifiedName(
                  child,
                  child.getAttribute("element"),
                );
              }
            });
            operationDetails.set(operationName, existing);
          }
        });
      }
    });
  }

  private collectSchemas(
    root: Element,
    uri: string | undefined,
    schemas: WsdlSchemaEntry[],
  ): void {
    this.forEachChildElement(root, (element) => {
      if (
        (element.namespaceURI === WSDL_11_NS ||
          element.namespaceURI === WSDL_20_NS) &&
        element.localName === "types"
      ) {
        this.forEachChildElement(element, (schemaElement) => {
          if (
            schemaElement.namespaceURI === XML_SCHEMA_NS &&
            schemaElement.localName === "schema"
          ) {
            schemas.push({
              uri,
              targetNamespace:
                schemaElement.getAttribute("targetNamespace") || undefined,
              element: schemaElement,
            });
          }
        });
      }
    });
  }

  private extractLocalName(qName: string): string | null {
    const trimmed = qName.trim();
    if (!trimmed) {
      return null;
    }
    const parts = trimmed.split(":");
    return parts.length > 1 ? parts[1] : parts[0];
  }

  private resolveQualifiedName(
    node: Element,
    value: string | null,
  ): WsdlQualifiedName | undefined {
    if (!value) {
      return undefined;
    }
    const trimmed = value.trim();
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
    return {
      namespace: namespace || undefined,
      name: localName,
      raw: trimmed,
    };
  }

  private buildQualifiedNameKey(qName: WsdlQualifiedName | undefined): string {
    if (!qName) {
      return "";
    }
    return `${qName.namespace ?? ""}#${qName.name}`;
  }

  private findChildAddress(portElement: Element): Element | undefined {
    let current = portElement.firstChild;
    while (current) {
      if (current.nodeType === 1) {
        const childElement = current as Element;
        if (
          childElement.localName === "address" &&
          (childElement.namespaceURI === SOAP_11_NS ||
            childElement.namespaceURI === SOAP_12_NS)
        ) {
          return childElement;
        }
      }
      current = current.nextSibling;
    }
    return undefined;
  }

  private findFirstChildElement(
    element: Element,
    namespace: string,
    localName: string,
  ): Element | undefined {
    let current = element.firstChild;
    while (current) {
      if (current.nodeType === 1) {
        const child = current as Element;
        if (child.namespaceURI === namespace && child.localName === localName) {
          return child;
        }
      }
      current = current.nextSibling;
    }
    return undefined;
  }

  private traverseElements(
    element: Element,
    visitor: (element: Element) => void,
  ) {
    visitor(element);
    this.forEachChildElement(element, (child) =>
      this.traverseElements(child, visitor),
    );
  }

  private forEachChildElement(
    element: Element,
    visitor: (element: Element) => void,
  ) {
    let current = element.firstChild;
    while (current) {
      if (current.nodeType === 1) {
        visitor(current as Element);
      }
      current = current.nextSibling;
    }
  }

  private deduplicateEndpoints(endpoints: WsdlEndpoint[]): WsdlEndpoint[] {
    const map = new Map<string, WsdlEndpoint>();
    endpoints.forEach((endpoint) => {
      const key = `${endpoint.serviceName ?? ""}|${endpoint.endpointName ?? ""}|${endpoint.address ?? ""}`;
      if (!map.has(key)) {
        map.set(key, endpoint);
      }
    });
    return Array.from(map.values());
  }
}
