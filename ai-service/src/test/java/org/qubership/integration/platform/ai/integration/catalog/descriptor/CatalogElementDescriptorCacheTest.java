package org.qubership.integration.platform.ai.integration.catalog.descriptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;

@ExtendWith(MockitoExtension.class)
class CatalogElementDescriptorCacheTest {

  @Mock private CatalogRestClient catalogRestClient;

  private CatalogElementDescriptorCache cache;

  @BeforeEach
  void setUp() {
    cache = newAttempt();
  }

  @Test
  void firstLoadCallsCatalogAndSecondLoadInSameAttemptReusesCache() {
    when(catalogRestClient.getLibraryElement("condition")).thenReturn(conditionDto());

    CatalogElementDescriptor first = cache.require("condition");
    CatalogElementDescriptor second = cache.require("condition");

    assertSame(first, second);
    verify(catalogRestClient, times(1)).getLibraryElement("condition");
  }

  @Test
  void secondAttemptForSameTypeCallsCatalogAgain() {
    when(catalogRestClient.getLibraryElement("condition")).thenReturn(conditionDto());

    newAttempt().require("condition");
    newAttempt().require("condition");

    verify(catalogRestClient, times(2)).getLibraryElement("condition");
  }

  @Test
  void mapsCatalogDescriptorFieldsOntoReadModel() {
    when(catalogRestClient.getLibraryElement("condition")).thenReturn(conditionDto());

    CatalogElementDescriptor descriptor = cache.require("condition");

    assertEquals("condition", descriptor.name());
    assertTrue(descriptor.container());
    assertEquals(
        Map.of(
            "if", CatalogChildQuantity.ONE_OR_MANY,
            "else", CatalogChildQuantity.ONE_OR_ZERO),
        descriptor.allowedChildren());
    assertEquals(List.of("try-2", "catch-2"), descriptor.parentRestriction());
    assertTrue(descriptor.ordered());
    assertEquals("priority", descriptor.priorityProperty());
    assertTrue(descriptor.mandatoryInnerElement());
    assertTrue(descriptor.deprecated());
    assertTrue(descriptor.oldStyleContainer());
    assertTrue(descriptor.allowedInContainers());
  }

  @Test
  void emptyAllowedChildrenOnContainerMeansNoChildTypeRestriction() {
    CatalogElementDescriptorDto dto = new CatalogElementDescriptorDto();
    dto.name = "script-container";
    dto.container = true;
    dto.allowedChildren = Map.of();
    when(catalogRestClient.getLibraryElement("script-container")).thenReturn(dto);

    CatalogElementDescriptor descriptor = cache.require("script-container");

    assertTrue(descriptor.container());
    assertEquals(Map.of(), descriptor.allowedChildren());
  }

  @Test
  void notFoundResponseFailsWithTypeNameAndDoesNotMutateCatalog() {
    when(catalogRestClient.getLibraryElement("unknown-type"))
        .thenThrow(new WebApplicationException(Response.status(404).build()));

    CatalogElementDescriptorException thrown =
        assertThrows(CatalogElementDescriptorException.class, () -> cache.require("unknown-type"));

    assertTrue(thrown.getMessage().contains("unknown-type"));
    verify(catalogRestClient).getLibraryElement("unknown-type");
    verify(catalogRestClient, never()).createElement(any(), any());
    verifyNoMoreInteractions(catalogRestClient);
  }

  @Test
  void nullDescriptorFailsWithTypeNameAndDoesNotMutateCatalog() {
    when(catalogRestClient.getLibraryElement("unknown-type")).thenReturn(null);

    CatalogElementDescriptorException thrown =
        assertThrows(CatalogElementDescriptorException.class, () -> cache.require("unknown-type"));

    assertTrue(thrown.getMessage().contains("unknown-type"));
    verify(catalogRestClient).getLibraryElement("unknown-type");
    verify(catalogRestClient, never()).createElement(any(), any());
    verifyNoMoreInteractions(catalogRestClient);
  }

  @Test
  void transportFailureFailsWithTypeName() {
    when(catalogRestClient.getLibraryElement("condition"))
        .thenThrow(new ProcessingException("Connection refused"));

    CatalogElementDescriptorException thrown =
        assertThrows(CatalogElementDescriptorException.class, () -> cache.require("condition"));

    assertTrue(thrown.getMessage().contains("condition"));
  }

  @Test
  void deserializesQuantityJsonValues() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String json =
        """
        {
          "name": "split-2",
          "container": true,
          "allowedChildren": {
            "one-child": "one",
            "optional-child": "one-or-zero",
            "many-child": "one-or-many",
            "pair-child": "two-or-many",
            "any-child": "any"
          }
        }
        """;

    CatalogElementDescriptorDto dto = mapper.readValue(json, CatalogElementDescriptorDto.class);

    assertEquals(CatalogChildQuantity.ONE, dto.allowedChildren.get("one-child"));
    assertEquals(CatalogChildQuantity.ONE_OR_ZERO, dto.allowedChildren.get("optional-child"));
    assertEquals(CatalogChildQuantity.ONE_OR_MANY, dto.allowedChildren.get("many-child"));
    assertEquals(CatalogChildQuantity.TWO_OR_MANY, dto.allowedChildren.get("pair-child"));
    assertEquals(CatalogChildQuantity.ANY, dto.allowedChildren.get("any-child"));
  }

  private CatalogElementDescriptorCache newAttempt() {
    return new CatalogElementDescriptorCache(
        new CatalogElementDescriptorLoader(catalogRestClient));
  }

  private static CatalogElementDescriptorDto conditionDto() {
    CatalogElementDescriptorDto dto = new CatalogElementDescriptorDto();
    dto.name = "condition";
    dto.container = true;
    dto.allowedChildren =
        Map.of(
            "if", CatalogChildQuantity.ONE_OR_MANY,
            "else", CatalogChildQuantity.ONE_OR_ZERO);
    dto.parentRestriction = List.of("try-2", "catch-2");
    dto.ordered = true;
    dto.priorityProperty = "priority";
    dto.mandatoryInnerElement = true;
    dto.deprecated = true;
    dto.oldStyleContainer = true;
    dto.allowedInContainers = true;
    return dto;
  }
}
