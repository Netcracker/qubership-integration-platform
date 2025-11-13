import * as protobuf from "protobufjs";
import { ProtoOperationResolver, buildProtoOperationSpecification } from "./proto/ProtoOperationResolver";
import { ProtoTypeDefinitionBuilder } from "./proto/ProtoTypeDefinitionBuilder";
import type { ProtoData, ProtoMethod, ProtoService } from "./proto/ProtoTypes";

export class ProtoSpecificationParser {
    static async parseProtoContent(content: string): Promise<ProtoData> {
        const parsed = protobuf.parse(content, { keepCase: true, alternateCommentMode: true });
        const root = parsed.root;
        root.resolveAll();

        const packageName = parsed.package ?? "";
        const javaPackage = this.extractJavaPackage(content) ?? packageName;

        const services = this.collectServices(root);
        const typeDefinitions = ProtoTypeDefinitionBuilder.build(root);

        return {
            type: "PROTO",
            packageName,
            package: packageName,
            javaPackage,
            services,
            typeDefinitions
        };
    }

    static createOperationsFromProto(protoData: ProtoData, specificationId: string): any[] {
        const resolver = new ProtoOperationResolver(protoData);
        const resolvedOperations = resolver.resolve();

        return resolvedOperations.map((operation) => {
            const requestSchema = cloneSchema(operation.requestSchema);
            const responseSchema = cloneSchema(operation.responseSchema);

            return {
                id: `${specificationId}-${operation.operationId}`,
                name: operation.operationId,
                method: operation.rpcName,
                path: operation.path,
                specification: buildProtoOperationSpecification(operation, requestSchema, responseSchema),
                requestSchema: {
                    "application/json": requestSchema
                },
                responseSchemas: {
                    "200": {
                        "application/json": responseSchema
                    }
                }
            };
        });
    }

    private static collectServices(root: protobuf.Root): ProtoService[] {
        const services: ProtoService[] = [];
        const queue: protobuf.NamespaceBase[] = [root];

        while (queue.length > 0) {
            const namespace = queue.shift()!;
            const nested = namespace.nestedArray ?? [];

            for (const element of nested) {
                if (element instanceof protobuf.Service) {
                    services.push(this.convertService(element));
                } else if (element instanceof protobuf.Namespace || element instanceof protobuf.Type) {
                    queue.push(element);
                }
            }
        }

        return services;
    }

    private static convertService(service: protobuf.Service): ProtoService {
        const qualifiedName = normalizeFullName(service.fullName);
        const servicePackage = extractPackageName(qualifiedName);
        const methods = service.methodsArray.map((method) =>
            this.convertMethod(service, method, servicePackage)
        );

        return {
            name: service.name,
            qualifiedName,
            methods
        };
    }

    private static convertMethod(
        service: protobuf.Service,
        method: protobuf.Method,
        servicePackage: string
    ): ProtoMethod {
        const operationId = `${service.name}.${method.name}`;
        const requestType = resolveMethodType(method.resolvedRequestType, method.requestType, servicePackage);
        const responseType = resolveMethodType(method.resolvedResponseType, method.responseType, servicePackage);

        return {
            name: method.name,
            operationId,
            comment: method.comment ?? undefined,
            requestType,
            responseType
        };
    }

    private static extractJavaPackage(content: string): string | undefined {
        const match = content.match(/option\s+java_package\s*=\s*"([^"]+)"/);
        return match ? match[1] : undefined;
    }
}

function resolveMethodType(
    resolved: protobuf.ReflectionObject | null | undefined,
    declaredType: string,
    servicePackage: string
): string {
    if (resolved && "fullName" in resolved && typeof resolved.fullName === "string") {
        return normalizeFullName(resolved.fullName);
    }
    return ensureFullyQualified(declaredType, servicePackage);
}

function ensureFullyQualified(typeName: string, defaultPackage: string): string {
    if (!typeName) {
        return typeName;
    }
    if (typeName.startsWith(".")) {
        return typeName.substring(1);
    }
    if (typeName.includes(".")) {
        return typeName;
    }
    return defaultPackage ? `${defaultPackage}.${typeName}` : typeName;
}

function extractPackageName(qualifiedName: string): string {
    const lastDot = qualifiedName.lastIndexOf(".");
    return lastDot >= 0 ? qualifiedName.substring(0, lastDot) : "";
}

function normalizeFullName(fullName: string): string {
    return fullName.startsWith(".") ? fullName.substring(1) : fullName;
}

function cloneSchema<T>(schema: T): T {
    return JSON.parse(JSON.stringify(schema));
}
