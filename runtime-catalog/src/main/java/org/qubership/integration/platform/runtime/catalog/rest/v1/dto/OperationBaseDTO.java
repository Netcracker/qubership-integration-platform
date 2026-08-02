/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.runtime.catalog.rest.v1.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.qubership.integration.platform.runtime.catalog.model.dto.BaseResponse;

import java.util.List;

@Data
@Schema(description = "Operation object")
public class OperationBaseDTO {
    @Schema(description = "Id")
    private String id;
    @Schema(description = "Description")
    private String description;
    @Schema(description = "Operation name (tag)")
    private String name;
    @Schema(description = "Operation method")
    private String method;
    @Schema(description = "Path")
    private String path;
    @Schema(description = "Specification id")
    private String modelId;
    @Schema(description = "Protocol discriminator: openapi, asyncapi, wsdl, graphql or protobuf")
    private String operationKind;
    @Schema(description = "Channel (asyncapi)")
    private String channel;
    @Schema(description = "Summary (openapi, asyncapi)")
    private String summary;
    @Schema(description = "Whether the operation is deprecated (openapi)")
    private Boolean isDeprecated;
    @Schema(description = "Operation type: query or mutation (graphql)")
    private String operationType;
    @Schema(description = "Binding (wsdl)")
    private String binding;
    @Schema(description = "RPC method (protobuf)")
    private String rpcMethod;
    @Schema(description = "Service (protobuf)")
    private String service;
    @Schema(description = "List of chains using current operation")
    private List<BaseResponse> chains;

    // "package" is a Java keyword, so the field is named packageName and its accessors expose the "package" property.
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @Schema(description = "Proto package (protobuf)")
    private String packageName;

    public String getPackage() {
        return packageName;
    }

    public void setPackage(String packageName) {
        this.packageName = packageName;
    }
}
