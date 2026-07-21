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

package org.qubership.integration.platform.runtime.catalog.service.verification.properties.verifiers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.library.components.LibraryElementsService;
import org.qubership.integration.platform.library.model.CustomTab;
import org.qubership.integration.platform.library.model.ElementDescriptor;
import org.qubership.integration.platform.library.model.ElementProperties;
import org.qubership.integration.platform.library.model.ElementProperty;
import org.qubership.integration.platform.library.model.PropertyValidation;
import org.qubership.integration.platform.library.model.PropertyValueType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Dependency;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers {@link MandatoryPropertyVerificationHelper}: an unknown type passes, a failing custom-tab
 * or a missing mandatory property fails, custom-typed properties defer to their validation, and the
 * mandatory-inner-element rule branches on container children versus a leaf's output dependencies.
 */
@ExtendWith(MockitoExtension.class)
class MandatoryPropertyVerificationHelperTest {

    @Mock
    private LibraryElementsService libraryService;
    @Mock
    private ElementDescriptor descriptor;

    private MandatoryPropertyVerificationHelper helper() {
        return new MandatoryPropertyVerificationHelper(libraryService);
    }

    private static ChainElement element(String type) {
        return ChainElement.builder().type(type).build();
    }

    @Test
    void areMandatoryPropertiesPresentPassesWhenTypeHasNoDescriptor() {
        when(libraryService.lookupElementDescriptor("unknown")).thenReturn(Optional.empty());

        assertThat(helper().areMandatoryPropertiesPresent(element("unknown"))).isTrue();
    }

    @Test
    void areMandatoryPropertiesPresentFailsWhenCustomTabValidationFails() {
        CustomTab tab = mock(CustomTab.class);
        PropertyValidation validation = mock(PropertyValidation.class);
        when(tab.getValidation()).thenReturn(validation);
        when(validation.arePropertiesValid(any())).thenReturn(false);
        when(descriptor.getCustomTabs()).thenReturn(List.of(tab));
        when(libraryService.lookupElementDescriptor("t")).thenReturn(Optional.of(descriptor));

        assertThat(helper().areMandatoryPropertiesPresent(element("t"))).isFalse();
    }

    @Test
    void areMandatoryPropertiesPresentFailsWhenMandatoryPropertyIsMissing() {
        ElementProperty property = mock(ElementProperty.class);
        when(property.isMandatory()).thenReturn(true);
        when(property.getName()).thenReturn("timeout");
        stubDescriptorProperties(List.of(property));

        assertThat(helper().areMandatoryPropertiesPresent(element("t"))).isFalse();
    }

    @Test
    void areMandatoryPropertiesPresentPassesWhenEveryMandatoryPropertyHasAValue() {
        ElementProperty property = mock(ElementProperty.class);
        when(property.isMandatory()).thenReturn(true);
        when(property.getName()).thenReturn("timeout");
        stubDescriptorProperties(List.of(property));

        ChainElement element = element("t");
        element.getProperties().put("timeout", "5000");

        assertThat(helper().areMandatoryPropertiesPresent(element)).isTrue();
    }

    @Test
    void isMandatoryPropertyPresentDefersToValidationForCustomProperties() {
        ElementProperty property = mock(ElementProperty.class);
        PropertyValidation validation = mock(PropertyValidation.class);
        when(property.getType()).thenReturn(PropertyValueType.CUSTOM);
        when(property.getValidation()).thenReturn(validation);
        when(validation.arePropertiesValid(any())).thenReturn(false);

        assertThat(helper().isMandatoryPropertyPresent(property, element("t"))).isFalse();
    }

    @Test
    void isMandatoryPropertyPresentPassesForAnOptionalProperty() {
        ElementProperty property = mock(ElementProperty.class);
        when(property.isMandatory()).thenReturn(false);

        assertThat(helper().isMandatoryPropertyPresent(property, element("t"))).isTrue();
    }

    @Test
    void isMandatoryPropertyPresentTracksAMandatoryValue() {
        ElementProperty property = mock(ElementProperty.class);
        when(property.isMandatory()).thenReturn(true);
        when(property.getName()).thenReturn("timeout");

        ChainElement missing = element("t");
        ChainElement present = element("t");
        present.getProperties().put("timeout", "5000");

        assertThat(helper().isMandatoryPropertyPresent(property, missing)).isFalse();
        assertThat(helper().isMandatoryPropertyPresent(property, present)).isTrue();
    }

    @Test
    void isMandatoryInnerElementPresentPassesWhenTheElementDoesNotRequireOne() {
        when(descriptor.isMandatoryInnerElement()).thenReturn(false);
        when(libraryService.lookupElementDescriptor("t")).thenReturn(Optional.of(descriptor));

        assertThat(helper().isMandatoryInnerElementPresent(element("t"))).isTrue();
    }

    @Test
    void isMandatoryInnerElementPresentChecksContainerChildrenForAStartElement() {
        when(descriptor.isMandatoryInnerElement()).thenReturn(true);
        when(libraryService.lookupElementDescriptor("container")).thenReturn(Optional.of(descriptor));

        ContainerChainElement withStart = new ContainerChainElement();
        withStart.setType("container");
        withStart.addChildElement(element("child"));

        ContainerChainElement withoutStart = new ContainerChainElement();
        withoutStart.setType("container");
        ChainElement wiredChild = element("child");
        wiredChild.addInputDependency(Dependency.of(element("other"), wiredChild));
        withoutStart.addChildElement(wiredChild);

        assertThat(helper().isMandatoryInnerElementPresent(withStart)).isTrue();
        assertThat(helper().isMandatoryInnerElementPresent(withoutStart)).isFalse();
    }

    @Test
    void isMandatoryInnerElementPresentChecksOutputDependenciesForALeaf() {
        when(descriptor.isMandatoryInnerElement()).thenReturn(true);
        when(libraryService.lookupElementDescriptor("t")).thenReturn(Optional.of(descriptor));

        ChainElement leaf = element("t");
        ChainElement wired = element("t");
        wired.addOutputDependency(Dependency.of(wired, element("other")));

        assertThat(helper().isMandatoryInnerElementPresent(leaf)).isFalse();
        assertThat(helper().isMandatoryInnerElementPresent(wired)).isTrue();
    }

    private void stubDescriptorProperties(List<ElementProperty> properties) {
        ElementProperties holder = mock(ElementProperties.class);
        when(holder.getAll()).thenReturn(properties);
        when(descriptor.getCustomTabs()).thenReturn(List.of());
        when(descriptor.getProperties()).thenReturn(holder);
        when(libraryService.lookupElementDescriptor("t")).thenReturn(Optional.of(descriptor));
    }
}
