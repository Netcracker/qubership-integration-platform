package org.qubership.integration.platform.camelk.sources.builders.xml.beans.builders.element;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.chain.impl.ConnectionImpl;
import org.qubership.integration.platform.chain.impl.ElementBuilder;
import org.qubership.integration.platform.chain.model.Element;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WireTapInfoBeanBuilderTest {

    @Test
    void shouldJoinSnapshotIdsOfInputElements() {
        Element first = input("11111111-1111-1111-1111-111111111111", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Element second = input("22222222-2222-2222-2222-222222222222", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        Element element = ElementBuilder.createNew()
                .id("33333333-3333-3333-3333-333333333333")
                .inputConnections(List.of(new ConnectionImpl(first, null), new ConnectionImpl(second, null)))
                .build();

        assertEquals(
                "11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222",
                new WireTapInfoBeanBuilder().getWireTapId(element));
    }

    private Element input(String id, String originalId) {
        return ElementBuilder.createNew().id(id).originalId(originalId).type("async-split-element").build();
    }
}
