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

package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.LibraryElements;
import org.qubership.integration.platform.runtime.catalog.model.dto.chain.ElementsFilterDTO;
import org.qubership.integration.platform.runtime.catalog.service.ElementService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.library.constants.CamelNames.CONTAINER;

/**
 * Covers the read endpoints of {@link ElementLibraryController}: the hierarchy pass-through, the
 * single-element lookup that returns 404 when the descriptor is absent, and the element-types
 * endpoint that drops the container type and maps each remaining type to its title.
 */
@ExtendWith(MockitoExtension.class)
class ElementLibraryControllerTest {

    @Mock
    private LibraryElementsService libraryElementsService;
    @Mock
    private ElementService elementService;

    private ElementLibraryController controller() {
        return new ElementLibraryController(libraryElementsService, elementService);
    }

    @Test
    void getElementsHierarchyReturnsTheLibraryHierarchy() {
        LibraryElements hierarchy = new LibraryElements();
        when(libraryElementsService.getElementsHierarchy()).thenReturn(hierarchy);

        ResponseEntity<LibraryElements> response = controller().getElementsHierarchy();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(hierarchy);
    }

    @Test
    void getLibraryElementReturnsTheDescriptorWhenFound() {
        ElementDescriptor descriptor = mock(ElementDescriptor.class);
        when(libraryElementsService.lookupElementDescriptor("http-trigger")).thenReturn(Optional.of(descriptor));

        ResponseEntity<ElementDescriptor> response = controller().getLibraryElement("http-trigger");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(descriptor);
    }

    @Test
    void getLibraryElementReturnsNotFoundWhenTheTypeIsUnknown() {
        when(libraryElementsService.lookupElementDescriptor("missing")).thenReturn(Optional.empty());

        ResponseEntity<ElementDescriptor> response = controller().getLibraryElement("missing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void findAllUsingElementsTypesDropsContainerAndMapsTitles() {
        ElementDescriptor httpDescriptor = mock(ElementDescriptor.class);
        when(httpDescriptor.getTitle()).thenReturn("HTTP Trigger");
        ElementDescriptor serviceDescriptor = mock(ElementDescriptor.class);
        when(serviceDescriptor.getTitle()).thenReturn("Service Call");

        when(elementService.findAllUsingTypes()).thenReturn(List.of("http-trigger", CONTAINER, "service-call"));
        when(libraryElementsService.getElementDescriptor("http-trigger")).thenReturn(httpDescriptor);
        when(libraryElementsService.getElementDescriptor("service-call")).thenReturn(serviceDescriptor);

        ResponseEntity<List<ElementsFilterDTO>> response = controller().findAllUsingElementsTypes();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .extracting(ElementsFilterDTO::getElementType, ElementsFilterDTO::getElementTitle)
                .containsExactly(
                        tuple("http-trigger", "HTTP Trigger"),
                        tuple("service-call", "Service Call"));
    }
}
