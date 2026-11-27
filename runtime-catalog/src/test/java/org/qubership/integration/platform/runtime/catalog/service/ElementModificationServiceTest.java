package org.qubership.integration.platform.runtime.catalog.service;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationGroup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElementModificationServiceTest {

    @Test
    void removesContextPathWhenMakingTriggerImplemented() {
        SpecificationGroupService specificationGroupService = mock(SpecificationGroupService.class);
        ElementService elementService = mock(ElementService.class);
        ChainElement trigger = ChainElement.builder()
                .id("trigger-id")
                .properties(new HashMap<>(Map.of("contextPath", "before")))
                .build();
        SpecificationGroup group = SpecificationGroup.builder()
                .id("group-id")
                .system(IntegrationSystem.builder().id("system-id").build())
                .systemModels(List.of())
                .build();
        when(specificationGroupService.getById("group-id")).thenReturn(group);
        when(elementService.findById("trigger-id")).thenReturn(trigger);

        new ElementModificationService(specificationGroupService, elementService)
                .makeHttpTriggersTypeImplemented(List.of("trigger-id"), "group-id");

        assertThat(trigger.getProperties()).doesNotContainKey("contextPath");
    }
}
