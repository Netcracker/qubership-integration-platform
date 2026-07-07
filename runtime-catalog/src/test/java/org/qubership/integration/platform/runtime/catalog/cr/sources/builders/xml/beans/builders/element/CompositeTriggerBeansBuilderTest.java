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

package org.qubership.integration.platform.runtime.catalog.cr.sources.builders.xml.beans.builders.element;

import org.codehaus.stax2.XMLStreamWriter2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.cr.sources.SourceBuilderContext;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementDescriptor;
import org.qubership.integration.platform.runtime.catalog.model.library.ElementType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.service.library.LibraryElementsService;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompositeTriggerBeansBuilderTest {

    @Mock
    private LibraryElementsService libraryService;
    @Mock
    private CommonBeansBuilder commonBeansBuilder;

    private CompositeTriggerBeansBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new CompositeTriggerBeansBuilder(libraryService, commonBeansBuilder);
    }

    private ElementDescriptor descriptorOfType(ElementType type) {
        ElementDescriptor descriptor = new ElementDescriptor();
        descriptor.setType(type);
        return descriptor;
    }

    @Test
    void testApplicableToReturnsTrueForCompositeTrigger() {
        ChainElement element = ChainElement.builder().build();
        when(libraryService.getElementDescriptor(element))
                .thenReturn(descriptorOfType(ElementType.COMPOSITE_TRIGGER));

        assertTrue(builder.applicableTo(element));
    }

    @Test
    void testApplicableToReturnsFalseForOtherTypes() {
        ChainElement element = ChainElement.builder().build();
        when(libraryService.getElementDescriptor(element))
                .thenReturn(descriptorOfType(ElementType.TRIGGER));

        assertFalse(builder.applicableTo(element));
    }

    @Test
    void testBuildDelegatesToCommonBeansBuilderWithCopiedElement() throws Exception {
        ChainElement element = ChainElement.builder()
                .id("original-id")
                .originalId("root-id")
                .build();
        Dependency dependency = Dependency.of(element, ChainElement.builder().build());
        element.getOutputDependencies().add(dependency);

        XMLStreamWriter2 streamWriter = mock(XMLStreamWriter2.class);
        SourceBuilderContext context = SourceBuilderContext.builder().domainName("domain").build();

        builder.build(streamWriter, element, context);

        ArgumentCaptor<ChainElement> captor = ArgumentCaptor.forClass(ChainElement.class);
        verify(commonBeansBuilder).build(same(streamWriter), captor.capture(), same(context));

        ChainElement trigger = captor.getValue();
        assertNotSame(element, trigger);
        assertEquals(UUID.nameUUIDFromBytes("original-id".getBytes()).toString(), trigger.getId());
        assertEquals("root-id", trigger.getOriginalId());
        assertEquals(List.of(dependency), trigger.getOutputDependencies());
    }
}
