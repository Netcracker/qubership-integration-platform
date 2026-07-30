package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.SystemModelBaseDTO;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SystemModelBaseMapperTypedTest {

    private final SystemModelBaseMapper mapper = createMapper();

    private static SystemModelBaseMapper createMapper() {
        SystemModelBaseMapper mapper = Mappers.getMapper(SystemModelBaseMapper.class);
        // ChainBaseMapper is Spring-autowired; wire it by hand since this test runs without a context.
        ReflectionTestUtils.setField(mapper, "chainBaseMapper", Mappers.getMapper(ChainBaseMapper.class));
        return mapper;
    }

    @Test
    void toDtoCarriesSpecificationTypeAndVersion() {
        SystemModel model = new SystemModel();
        model.setSpecificationType("openapi");
        model.setSpecificationVersion("3.0.1");

        SystemModelBaseDTO dto = mapper.toDTO(model);

        assertEquals("openapi", dto.getSpecificationType());
        assertEquals("3.0.1", dto.getSpecificationVersion());
    }
}
