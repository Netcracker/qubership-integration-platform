package org.qubership.integration.platform.runtime.catalog.service.library;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.element.descriptor.ElementDescriptorProperties;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementFolder;
import org.qubership.integration.platform.runtime.catalog.model.library.LibraryElements;
import org.springframework.util.PropertyPlaceholderHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class LibraryElementsServiceTest {

    @Mock
    YAMLMapper yamlMapper;

    @Mock
    PropertyPlaceholderHelper propertyPlaceholderHelper;

    @Mock
    ElementDescriptorProperties elementDescriptorProperties;

    @Mock
    Properties descriptorProperties;

    LibraryElementsService service;

    @BeforeEach
    void setUp() {
        when(elementDescriptorProperties.getProperties()).thenReturn(descriptorProperties);
        service = new LibraryElementsService(yamlMapper, propertyPlaceholderHelper, elementDescriptorProperties);
    }

    @Test
    void shouldReturnEmptyHierarchyWhenNoElementsOrFolders() {
        LibraryElements result = service.getElementsHierarchy();

        assertThat(result.getElements()).isEmpty();
    }

    @Test
    void shouldReturnNullWhenElementNotFound() {
        ElementDescriptor result = service.getElementDescriptor("nonexistent");

        assertThat(result).isNull();
    }

    @Test
    void shouldHandleEmptyList() {
        service.registerFolders(Collections.emptyList());

        LibraryElements result = service.getElementsHierarchy();

        assertThat(result.getElements()).isEmpty();
    }

    @Test
    void shouldRegisterAndGetFolder() {
        ElementFolder folder = new ElementFolder();
        folder.setName("my-folder");

        service.registerFolder(folder);

        ElementFolder result = service.getFolder("my-folder");
        assertThat(result).isSameAs(folder);
    }


    @Test
    void shouldLoadElementPatch() throws Exception {
        String yaml = "key: value";
        InputStream is = new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
        JsonNode node = mock(JsonNode.class);

        when(yamlMapper.readTree(any(Reader.class))).thenReturn(node);

        service.loadElementPatch("element1", is);

        Field field = LibraryElementsService.class.getDeclaredField("elementPatches");
        field.setAccessible(true);
        Map<String, JsonNode> patches = (Map<String, JsonNode>) field.get(service);

        assertThat(patches).containsEntry("element1", node);
    }

    @Test
    void shouldLoadElementDescriptorWithoutPatch() throws Exception {
        JsonNode descriptorNode = mock(JsonNode.class);
        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setName("test");

        InputStream inputStream = new ByteArrayInputStream("irrelevant".getBytes());

        when(propertyPlaceholderHelper.replacePlaceholders(any(), eq(descriptorProperties))).thenReturn("processed-yaml");
        when(yamlMapper.readTree("processed-yaml")).thenReturn(descriptorNode);
        when(yamlMapper.convertValue(eq(descriptorNode), eq(ElementDescriptor.class))).thenReturn(descriptor);

        ElementDescriptor result = service.loadElementDescriptor("element1", inputStream);

        assertThat(result).isEqualTo(descriptor);
        assertThat(service.getElementDescriptor("test")).isEqualTo(descriptor);
    }
}
