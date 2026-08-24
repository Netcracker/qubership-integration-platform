package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.qubership.integration.platform.io.model.exportimport.system.ApiOperationDto;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.AsyncapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.GraphqlOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.OpenapiOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.ProtobufOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.TypedOperation;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// Maps the operation entity to its flat export projection and back. The type-specific fields flow through Operation's
// delegating accessors; method, path and protocol are read straight off typed, which keeps the openapi method
// lowercase (getMethod is the uppercase derived column). graphql sdl and protobuf javaPackage are carried too: they
// are the only inputs that reconstruct path for those protocols, and path stays suppressed for both (it would hold
// the sdl blob for graphql and a derived join for protobuf).
@Component
public class ApiOperationDtoMapper {

    public List<ApiOperationDto> toDtos(List<Operation> operations) {
        return operations == null
                ? new ArrayList<>()
                : operations.stream().map(this::toDto).collect(Collectors.toCollection(ArrayList::new));
    }

    public ApiOperationDto toDto(Operation operation) {
        TypedOperation typed = operation.getTyped();
        return ApiOperationDto.builder()
                .id(operation.getId())
                .name(operation.getName())
                .description(operation.getDescription())
                .type(operation.getOperationKind())
                .summary(operation.getSummary())
                .path(exportedPath(operation, typed))
                .method(typed == null ? operation.getMethod() : rawMethod(typed))
                .isDeprecated(operation.getIsDeprecated())
                .channel(operation.getChannel())
                .protocol(typed instanceof WsdlOperation op ? op.protocol() : null)
                .binding(operation.getBinding())
                .operationType(operation.getOperationType())
                .sdl(typed instanceof GraphqlOperation op ? op.sdl() : null)
                .packageName(operation.getPackage())
                .service(operation.getService())
                .rpcMethod(operation.getRpcMethod())
                .javaPackage(typed instanceof ProtobufOperation op ? op.javaPackage() : null)
                .specification(operation.getSpecification())
                .build();
    }

    // With a typed operation, path/method reconstruct from typed on import (openapi carries the literal
    // path; the rest derive), so they stay null here. When typed is null (METAMODEL, or a legacy op whose
    // protocol did not resolve), fall back to the stored columns, or the round trip drops them silently.
    private static String exportedPath(Operation operation, TypedOperation typed) {
        if (typed == null) {
            return operation.getPath();
        }
        return typed instanceof OpenapiOperation op ? op.path() : null;
    }

    public List<Operation> toEntities(List<ApiOperationDto> operations) {
        return operations == null
                ? new ArrayList<>()
                : operations.stream().map(this::toEntity).collect(Collectors.toCollection(ArrayList::new));
    }

    public Operation toEntity(ApiOperationDto dto) {
        Operation operation = Operation.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .method(dto.getMethod())
                .path(dto.getPath())
                .specification(dto.getSpecification())
                .build();
        TypedOperation typed = buildTyped(dto);
        if (typed != null) {
            operation.setTyped(typed);
        }
        return operation;
    }

    private static String rawMethod(TypedOperation typed) {
        if (typed instanceof OpenapiOperation op) {
            return op.method();
        }
        if (typed instanceof AsyncapiOperation op) {
            return op.method();
        }
        return null;
    }

    private static TypedOperation buildTyped(ApiOperationDto dto) {
        if (dto.getType() == null) {
            return null;
        }
        return switch (dto.getType()) {
            case "openapi" -> new OpenapiOperation(
                    dto.getSummary(), dto.getPath(), dto.getMethod(), dto.getIsDeprecated());
            case "asyncapi" -> new AsyncapiOperation(dto.getSummary(), dto.getChannel(), dto.getMethod());
            case "wsdl" -> new WsdlOperation(dto.getProtocol(), dto.getBinding());
            case "graphql" -> new GraphqlOperation(dto.getOperationType(), dto.getSdl());
            case "protobuf" -> new ProtobufOperation(
                    dto.getPackageName(), dto.getService(), dto.getRpcMethod(), dto.getJavaPackage());
            default -> null;
        };
    }
}
