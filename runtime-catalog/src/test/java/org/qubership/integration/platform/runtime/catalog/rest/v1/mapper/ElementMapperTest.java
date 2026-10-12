package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.runtime.catalog.adapters.ChainElementAdapter;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.UserMapper;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ContainerChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.element.ElementResponse;
import org.qubership.integration.platform.runtime.catalog.util.StringTrimmer;
import org.qubership.integration.platform.verification.properties.verifiers.MandatoryPropertyVerificationHelper;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the mandatory-checks flag of {@link ElementMapper#toElementResponse}: the verification helper
 * lives in the shared library and takes the library's {@link Element}, so the mapper hands it the entity
 * wrapped in a {@link ChainElementAdapter}.
 */
class ElementMapperTest {

    private final MandatoryPropertyVerificationHelper verificationHelper = mock(MandatoryPropertyVerificationHelper.class);

    private ElementMapper elementMapper;

    @BeforeEach
    void setUp() {
        ElementMapperImpl mapper = new ElementMapperImpl();
        ReflectionTestUtils.setField(mapper, "stringTrimmer", new StringTrimmer());
        ReflectionTestUtils.setField(mapper, "userMapper", mock(UserMapper.class));
        ReflectionTestUtils.setField(mapper, "mandatoryPropertyVerificationHelper", verificationHelper);
        elementMapper = mapper;
    }

    @Test
    void passesTheMandatoryChecksWhenPropertiesAndInnerElementsArePresent() {
        stubChecks(true, true);

        ElementResponse response = elementMapper.toElementResponse(element("element-1"));

        assertThat(response.getId()).isEqualTo("element-1");
        assertThat(response.isMandatoryChecksPassed()).isTrue();
    }

    @Test
    void failsTheMandatoryChecksWhenAMandatoryPropertyIsMissing() {
        stubChecks(false, true);

        assertThat(elementMapper.toElementResponse(element("element-1")).isMandatoryChecksPassed()).isFalse();
    }

    @Test
    void failsTheMandatoryChecksWhenAMandatoryInnerElementIsMissing() {
        stubChecks(true, false);

        assertThat(elementMapper.toElementResponse(element("element-1")).isMandatoryChecksPassed()).isFalse();
    }

    @Test
    void verifiesTheMappedEntityThroughAnAdapter() {
        stubChecks(true, true);
        ChainElement element = element("element-1");

        elementMapper.toElementResponse(element);

        ArgumentCaptor<Element> properties = ArgumentCaptor.forClass(Element.class);
        ArgumentCaptor<Element> innerElements = ArgumentCaptor.forClass(Element.class);
        verify(verificationHelper).areMandatoryPropertiesPresent(properties.capture());
        verify(verificationHelper).isMandatoryInnerElementPresent(innerElements.capture());
        assertThat(properties.getValue()).isInstanceOf(ChainElementAdapter.class);
        assertThat(((ChainElementAdapter) properties.getValue()).getChainElement()).isSameAs(element);
        assertThat(((ChainElementAdapter) innerElements.getValue()).getChainElement()).isSameAs(element);
    }

    @Test
    void appliesTheMandatoryChecksToAContainerElement() {
        stubChecks(true, false);
        ContainerChainElement container = ContainerChainElement.builder().id("container-1").type("try-catch-finally-2").build();

        ElementResponse response = elementMapper.toElementResponse(container);

        assertThat(response.getId()).isEqualTo("container-1");
        assertThat(response.isMandatoryChecksPassed()).isFalse();
        verify(verificationHelper).isMandatoryInnerElementPresent(any());
    }

    @Test
    void mapsNoElementToNullWithoutVerifying() {
        assertThat(elementMapper.toElementResponse(null)).isNull();
        verifyNoInteractions(verificationHelper);
    }

    private void stubChecks(boolean propertiesPresent, boolean innerElementPresent) {
        when(verificationHelper.areMandatoryPropertiesPresent(any())).thenReturn(propertiesPresent);
        when(verificationHelper.isMandatoryInnerElementPresent(any())).thenReturn(innerElementPresent);
    }

    private static ChainElement element(String id) {
        return ChainElement.builder().id(id).type("script").build();
    }
}
