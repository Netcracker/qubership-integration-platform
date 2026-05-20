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

package org.qubership.integration.platform.runtime.catalog.model.library.chaindesign;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.util.DiagramBuilderEscapeUtil;
import org.springframework.lang.Nullable;

@Setter
@RequiredArgsConstructor
@ToString
@EqualsAndHashCode
@Schema(description = "Element parameters for chain sequence diagram building")
public class ElementDesignParameters {

    @Schema(description = "Id of a participant of sequence diagram")
    private String externalParticipantId;
    /**
     * String to add a participant to sequence diagram. Placeholders like <code>${url}</code> can be used
     * for element parameters substitution. For example: "Kafka ${brokers}" -> "Kafka kafka-main:9092"
     */
    @Nullable
    @Schema(description = "Name of a participant of sequence diagram")
    private String externalParticipantName;

    /**
     * Text over sequence lines. Placeholders supported
     */
    @Schema(description = "Text over sequence lines. Placeholders supported")
    private String requestLineTitle;

    @Getter()
    @Schema(description = "Whether direction is to chain or from it")
    private boolean directionToChain;

    /**
     * Build default "Response" line or not
     */
    @Getter()
    @Schema(description = "Whether build default \"Response\" line or not")
    private boolean hasResponse = true;

    /**
     * Response immediately after request or after recursive returning
     */
    @Getter()
    @Schema(description = "Whether response immediately after request or after recursive returning")
    private boolean responseAfterRequest = true;

    public String getExternalParticipantId(String chainId, ChainElement currentElement) {
        String result = DiagramBuilderEscapeUtil.substituteProperties(chainId, currentElement, externalParticipantId);
        return DiagramBuilderEscapeUtil.removeOrReplaceUnsupportedCharacters(result);
    }

    public String getExternalParticipantName(String chainId, ChainElement currentElement) {
        return DiagramBuilderEscapeUtil.substituteProperties(chainId, currentElement, externalParticipantName);
    }

    public String getRequestLineTitle(String chainId, ChainElement currentElement) {
        return DiagramBuilderEscapeUtil.substituteProperties(chainId, currentElement, requestLineTitle);
    }
}
