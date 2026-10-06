package org.qubership.integration.platform.runtime.catalog.service.exportimport.mapper.services;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.io.model.exportimport.system.SystemModelDto;
import org.qubership.integration.platform.runtime.catalog.configuration.ApplicationJsonSchemaProperties;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemModelDtoMapperTest {

    @Test
    void testToExternalEntityWritesSpecificationSchema() {
        SystemModelDtoMapper mapper = new SystemModelDtoMapper(new ApplicationJsonSchemaProperties());
        SystemModel model = SystemModel.builder()
                .id("svc-1-group-1.0.0")
                .name("1.0.0")
                .specificationGroup(SpecificationGroup.builder().id("svc-1-group").build())
                .build();

        SystemModelDto result = mapper.toExternalEntity(model);

        assertEquals(
                URI.create("http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/specification"),
                result.getSchema());
    }
}
