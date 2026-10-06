package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.model.exportimport.system.SpecificationGroupDto;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpecificationGroupDtoMapperTest {

    @Test
    void testToExternalEntityWritesSpecificationGroupSchema() {
        SpecificationGroupDtoMapper mapper = new SpecificationGroupDtoMapper(new ApplicationJsonSchemaProperties());
        SpecificationGroup group = SpecificationGroup.builder()
                .id("svc-1-group")
                .name("group")
                .system(IntegrationSystem.builder().id("svc-1").build())
                .build();

        SpecificationGroupDto result = mapper.toExternalEntity(group);

        assertEquals(
                URI.create("http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/specification-group"),
                result.getSchema());
    }
}
