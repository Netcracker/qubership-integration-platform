package org.qubership.integration.platform.runtime.catalog.service.parsers.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.model.system.typed.WsdlOperation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.ApiGroup;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Operation;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.system.SystemModelRepository;
import org.qubership.integration.platform.runtime.catalog.service.parsers.ParserUtils;
import org.qubership.integration.platform.runtime.catalog.service.resolvers.wsdl.WsdlVersionParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers the WSDL parser's typed output. WSDL carries no public {@code parseOperations} core, so the test
 * drives the import path and asserts each operation gets a typed WsdlOperation whose derived method and
 * path stay the SOAP constants (POST and empty path).
 */
class WSDLSpecificationParserTest {

    private WSDLSpecificationParser parser;

    @BeforeEach
    void setUp() {
        SystemModelRepository systemModelRepository = mock(SystemModelRepository.class);
        when(systemModelRepository.save(any(SystemModel.class))).thenAnswer(inv -> inv.getArgument(0));

        ParserUtils parserUtils = mock(ParserUtils.class);
        when(parserUtils.defineVersionName(any(), any())).thenReturn("1.0.0");
        when(parserUtils.defineVersion(any(), any())).thenReturn("1.0.0");

        WsdlVersionParser wsdlVersionParser = new WsdlVersionParser(SAXParserFactory.newDefaultInstance());

        parser = new WSDLSpecificationParser(
                systemModelRepository, null, null, wsdlVersionParser, parserUtils, null);
    }

    @Test
    @DisplayName("import populates typed WsdlOperation with SOAP protocol and binding, method POST and empty path")
    void importPopulatesTypedWsdlOperation() throws Exception {
        String wsdl = readResource("conformance/wsdl-hello-service/source.input.wsdl");

        IntegrationSystem system = new IntegrationSystem("sys-id");
        system.setIntegrationSystemType(IntegrationSystemType.INTERNAL);

        ApiGroup group = ApiGroup.builder().name("grp").build();
        group.setId("grp-id");
        group.setSystem(system);

        SpecificationSource source = new SpecificationSource();
        source.setName("hello.wsdl");
        source.setSource(wsdl);
        source.setMainSource(true);

        SystemModel model = parser.enrichSpecificationGroup(
                group, List.of(source), new HashSet<>(), false, false, message -> { });

        assertEquals(1, model.getOperations().size());
        Operation operation = model.getOperations().getFirst();

        WsdlOperation typed = assertInstanceOf(WsdlOperation.class, operation.getTyped());
        assertEquals("SOAP", typed.protocol());
        assertEquals("HelloBinding", typed.binding());

        // Anti-regression: WSDL derives a constant method and empty path, unchanged from the pre-typed values.
        assertEquals("POST", operation.getMethod());
        assertEquals("", operation.getPath());
        assertNotNull(operation.getName());
    }

    private String readResource(String path) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(is, "Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
