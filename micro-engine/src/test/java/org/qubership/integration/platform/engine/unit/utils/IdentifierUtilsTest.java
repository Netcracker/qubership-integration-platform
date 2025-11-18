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

package org.qubership.integration.platform.engine.unit.utils;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.IdentifierUtils;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class IdentifierUtilsTest {

    //TODO uncomment if method really usefully
    //    @Test
    //    void should_return_chain_uuid_and_name_when_routeId_has_three_parts() {
    //        Exchange ex = mock(Exchange.class);
    //        when(ex.getFromRouteId()).thenReturn("c-uuid:ChainName:random-uuid");
    //
    //        var pair = IdentifierUtils.parseChainIdentifier(ex);
    //
    //        assertEquals("c-uuid", pair.getLeft());
    //        assertEquals("ChainName", pair.getRight());
    //    }
    //
    //    @Test
    //    void should_return_chain_uuid_and_name_when_routeId_has_exactly_two_parts() {
    //        Exchange ex = mock(Exchange.class);
    //        when(ex.getFromRouteId()).thenReturn("c-uuid:ChainName");
    //
    //        var pair = IdentifierUtils.parseChainIdentifier(ex);
    //
    //        assertEquals("c-uuid", pair.getLeft());
    //        assertEquals("ChainName", pair.getRight());
    //    }
    //
    //    @Test
    //    void should_throw_when_routeId_is_null() {
    //        Exchange ex = mock(Exchange.class);
    //        when(ex.getFromRouteId()).thenReturn(null);
    //
    //        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
    //                () -> IdentifierUtils.parseChainIdentifier(ex));
    //        assertTrue(e.getMessage().contains("Invalid session start route identifier"));
    //    }
    //
    //    @Test
    //    void should_throw_when_routeId_has_less_than_two_parts() {
    //        Exchange ex = mock(Exchange.class);
    //        when(ex.getFromRouteId()).thenReturn("only-one");
    //
    //        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
    //                () -> IdentifierUtils.parseChainIdentifier(ex));
    //        assertTrue(e.getMessage().contains("Invalid session start route identifier"));
    //    }

    @Test
    void shouldBuildElementIdentifierWhenThreePartsProvided() {
        var id = IdentifierUtils.spreadIdentifier("e-uuid:camelName:elementName");

        assertEquals("e-uuid", id.getElementId());
        assertEquals("camelName", id.getCamelElementName());
        assertEquals("elementName", id.getElementName());
        assertNull(id.getExternalElementId());
    }

    @Test
    void shouldBuildElementIdentifierWithExternalIdWhenFourPartsProvided() {
        var id = IdentifierUtils.spreadIdentifier("e-uuid:camelName:elementName:sys-uuid");

        assertEquals("e-uuid", id.getElementId());
        assertEquals("camelName", id.getCamelElementName());
        assertEquals("elementName", id.getElementName());
        assertEquals("sys-uuid", id.getExternalElementId());
    }

    @Test
    void shouldThrowWhenIdentifierIsNullOrHasLessThanThreeParts() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> IdentifierUtils.spreadIdentifier((String) null));
        assertTrue(e1.getMessage().contains("Invalid id format of element"));

        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> IdentifierUtils.spreadIdentifier("a:b"));
        assertTrue(e2.getMessage().contains("Invalid id format of element"));
    }

    //TODO uncomment if method really usefully
    //    @Test
    //    void should_build_from_NamedNode_id() {
    //        NamedNode node = mock(NamedNode.class);
    //        when(node.getId()).thenReturn("e1:camel:el");
    //
    //        ElementIdentifier id = IdentifierUtils.spreadIdentifier(node);
    //        assertEquals("e1", id.getElementId());
    //        assertEquals("camel", id.getCamelElementName());
    //        assertEquals("el", id.getElementName());
    //    }

    //TODO uncomment if method really usefully
    //    @Test
    //    void should_detect_service_element_by_exact_ids() {
    //        NamedNode wrap = mock(NamedNode.class);
    //        when(wrap.getId()).thenReturn("SESSION_WRAPPER");
    //        assertTrue(IdentifierUtils.isServiceElement(wrap));
    //        assertTrue(IdentifierUtils.isServiceElement("SESSION_WRAPPER"));
    //        assertTrue(IdentifierUtils.isServiceElement("SESSION_WRAPPER_CATCH"));
    //        assertTrue(IdentifierUtils.isServiceElement("SESSION_WRAPPER_CATCH_LOGGER"));
    //
    //        assertFalse(IdentifierUtils.isServiceElement("SESSION_WRAPPER:camel:name"));
    //        assertFalse(IdentifierUtils.isServiceElement("something_else"));
    //    }

    //TODO uncomment if method really usefully
    //    @Test
    //    void should_return_null_when_identifier_is_service_element_exact() {
    //        assertNull(IdentifierUtils.extractIdFromIdentifier("SESSION_WRAPPER"));
    //        assertNull(IdentifierUtils.extractIdFromIdentifier("SESSION_WRAPPER_CATCH"));
    //        assertNull(IdentifierUtils.extractIdFromIdentifier("SESSION_WRAPPER_CATCH_LOGGER"));
    //    }
    //
    //    @Test
    //    void should_return_element_id_when_identifier_is_regular() {
    //        assertEquals("el-uuid",
    //                IdentifierUtils.extractIdFromIdentifier("el-uuid:camelName:elementName"));
    //    }


    //TODO uncomment if method really usefully
    //    @Test
    //    void should_return_true_for_session_wrapper_when_first_part_matches() {
    //        NamedNode node = mock(NamedNode.class);
    //        when(node.getId()).thenReturn("SESSION_WRAPPER:camel:name");
    //        assertTrue(IdentifierUtils.isSessionWrapper(node));
    //    }
    //
    //    @Test
    //    void should_return_false_for_session_wrapper_when_first_part_differs_or_null() {
    //        NamedNode node1 = mock(NamedNode.class);
    //        when(node1.getId()).thenReturn("OTHER:camel:name");
    //        assertFalse(IdentifierUtils.isSessionWrapper(node1));
    //
    //        NamedNode node2 = mock(NamedNode.class);
    //        when(node2.getId()).thenReturn(null);
    //        assertFalse(IdentifierUtils.isSessionWrapper(node2));
    //    }
    //
    //    @Test
    //    void should_match_exact_ids_for_session_wrapper_catch_and_log() {
    //        NamedNode catchNode = mock(NamedNode.class);
    //        when(catchNode.getId()).thenReturn("SESSION_WRAPPER_CATCH");
    //        assertTrue(IdentifierUtils.isSessionWrapperCatch(catchNode));
    //
    //        NamedNode logNode = mock(NamedNode.class);
    //        when(logNode.getId()).thenReturn("SESSION_WRAPPER_CATCH_LOGGER");
    //        assertTrue(IdentifierUtils.isSessionWrapperCatchLog(logNode));
    //
    //        NamedNode other = mock(NamedNode.class);
    //        when(other.getId()).thenReturn("SESSION_WRAPPER_CATCH:extra");
    //        assertFalse(IdentifierUtils.isSessionWrapperCatch(other));
    //        when(other.getId()).thenReturn("SESSION_WRAPPER_CATCH_LOGGER:extra");
    //        assertFalse(IdentifierUtils.isSessionWrapperCatchLog(other));
    //    }

    @Test
    void shouldValidateLowercaseUUIDV4LikeFormat() {
        String u = "123e4567-e89b-12d3-a456-426614174000";
        assertTrue(IdentifierUtils.isValidUUID(u));
    }

    @Test
    void shouldRejectUppercaseOrMalformedUUID() {
        assertFalse(IdentifierUtils.isValidUUID("123E4567-E89B-12D3-A456-426614174000"));
        assertFalse(IdentifierUtils.isValidUUID("not-a-uuid"));
        assertFalse(IdentifierUtils.isValidUUID("123e4567-e89b-12d3-a456-42661417400"));
    }

    @Test
    void shouldBuildRetryIteratorPropertyNameFromElementId() {
        assertEquals("internalProperty_serviceCall_XYZ_Iterator",
                IdentifierUtils.getServiceCallRetryIteratorPropertyName("XYZ"));
    }

    @Test
    void shouldBuildRetryPropertyNameFromElementId() {
        assertEquals("internalProperty_serviceCall_XYZ_Retry",
                IdentifierUtils.getServiceCallRetryPropertyName("XYZ"));
    }
}
