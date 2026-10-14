package org.qubership.integration.platform.runtime.catalog.rest.v1.mapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.ChainLabel;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainLabelDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.chain.ChainRequest;
import org.qubership.integration.platform.runtime.catalog.util.StringTrimmer;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChainMapperTest {

    private ChainMapper chainMapper;

    @BeforeEach
    void setUp() {
        ChainMapperImpl mapper = new ChainMapperImpl();
        ReflectionTestUtils.setField(mapper, "stringTrimmer", new StringTrimmer());
        chainMapper = mapper;
    }

    @Test
    @DisplayName("asEntity leaves labels empty when the request omits them")
    void asEntityLeavesLabelsEmptyWhenTheRequestOmitsThem() {
        ChainRequest request = new ChainRequest();
        request.setName("chain");

        Chain chain = chainMapper.asEntity(request);

        assertThat(chain.getLabels())
                .as("an absent labels list must not overwrite the entity default with null")
                .isEmpty();
    }

    @Test
    @DisplayName("asEntity leaves labels empty when the request carries an empty list")
    void asEntityLeavesLabelsEmptyWhenTheRequestCarriesAnEmptyList() {
        ChainRequest request = new ChainRequest();
        request.setName("chain");
        request.setLabels(List.of());

        Chain chain = chainMapper.asEntity(request);

        assertThat(chain.getLabels()).isEmpty();
    }

    @Test
    @DisplayName("asEntity binds every mapped label back to the chain")
    void asEntityBindsEveryMappedLabelBackToTheChain() {
        ChainRequest request = new ChainRequest();
        request.setName("chain");
        request.setLabels(List.of(ChainLabelDTO.builder().name("L1").technical(false).build()));

        Chain chain = chainMapper.asEntity(request);

        assertThat(chain.getLabels()).singleElement()
                .extracting(ChainLabel::getName, ChainLabel::getChain)
                .containsExactly("L1", chain);
    }
}
