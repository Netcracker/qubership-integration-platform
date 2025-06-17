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

package org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.annotations.JdbcTypeCode;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;

import java.sql.Types;

@Getter
@Setter
@Slf4j
@SuperBuilder
@NoArgsConstructor
@Entity(name = "detailed_design_templates")
public class DetailedDesignTemplate extends AbstractEntity {
    @Lob
    @JdbcTypeCode(Types.LONGVARCHAR)
    @Column(name = "content", columnDefinition = "text")
    private String content;
}
