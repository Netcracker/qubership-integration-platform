package org.qubership.integration.platform.runtime.catalog.service.parsers;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationSimilarVersionException;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.SystemModelBaseService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

// The application-level duplicate check is the only thing keeping two specifications of a group off the same
// version until a database constraint ships, so every path into defineVersionName has to run it.
@ExtendWith(MockitoExtension.class)
class ParserUtilsTest {

    private static final String GROUP_ID = "specification-group-1";
    private static final String VERSION = "1.2.3";

    @Mock
    private SystemModelBaseService systemModelBaseService;

    private ParserUtils parserUtils;

    @BeforeEach
    void setUp() {
        parserUtils = new ParserUtils(systemModelBaseService, new ObjectMapper());
    }

    @Test
    @DisplayName("A version already present in the group is rejected")
    void rejectsDuplicateVersion() {
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, VERSION)).thenReturn(1L);
        ApiGroup group = specificationGroup();

        SpecificationSimilarVersionException exception = assertThrows(SpecificationSimilarVersionException.class,
                () -> parserUtils.defineVersionName(group, VERSION));

        assertEquals("Specification with the version '" + VERSION + "' already exists", exception.getMessage());
    }

    @Test
    @DisplayName("A version not yet present in the group is accepted")
    void acceptsUnusedVersion() {
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, VERSION)).thenReturn(0L);

        assertEquals(VERSION, parserUtils.defineVersionName(specificationGroup(), VERSION));
    }

    @Test
    @DisplayName("The version is taken from the OpenAPI info block and checked for duplicates")
    void rejectsDuplicateVersionFromOpenApiInfo() {
        OpenAPI specification = new OpenAPI().info(new Info().version(VERSION));
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, VERSION)).thenReturn(1L);
        ApiGroup group = specificationGroup();

        assertThrows(SpecificationSimilarVersionException.class,
                () -> parserUtils.defineVersionName(group, specification));
    }

    @Test
    @DisplayName("A generated version is checked for duplicates as well")
    void rejectsDuplicateGeneratedVersion() {
        when(systemModelBaseService.getSystemModelsBySpecificationGroupId(GROUP_ID))
                .thenReturn(List.of(SystemModel.builder().build()));
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, "2.0.0")).thenReturn(1L);
        ApiGroup group = specificationGroup();

        assertThrows(SpecificationSimilarVersionException.class,
                () -> parserUtils.defineVersionName(group, new Object()));
    }

    @Test
    @DisplayName("A generated version that is free is returned")
    void acceptsUnusedGeneratedVersion() {
        when(systemModelBaseService.getSystemModelsBySpecificationGroupId(GROUP_ID))
                .thenReturn(List.of(SystemModel.builder().build()));
        when(systemModelBaseService.countBySpecificationGroupIdAndVersion(GROUP_ID, "2.0.0")).thenReturn(0L);

        assertEquals("2.0.0", parserUtils.defineVersionName(specificationGroup(), new Object()));
    }

    private static ApiGroup specificationGroup() {
        return ApiGroup.builder().id(GROUP_ID).build();
    }
}
