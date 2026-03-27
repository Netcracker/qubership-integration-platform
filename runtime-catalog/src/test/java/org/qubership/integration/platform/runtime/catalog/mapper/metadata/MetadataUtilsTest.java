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

package org.qubership.integration.platform.runtime.catalog.mapper.metadata;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.model.mapper.metadata.Metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetadataUtilsTest {
    @Test
    void testGetDataFormat() {
        Metadata metadata = new Metadata();
        assertEquals(DataFormat.UNSPECIFIED, MetadataUtils.getDataFormat(metadata));

        metadata.put("dataFormat", "xml");
        assertEquals(DataFormat.XML, MetadataUtils.getDataFormat(metadata));

        metadata.put("dataFormat", "foo");
        assertThrows(
                IllegalArgumentException.class,
                () -> MetadataUtils.getDataFormat(metadata)
        );
    }

    @Test
    void testGetSourceFormat() {
        Metadata metadata = new Metadata();
        assertEquals(DataFormat.UNSPECIFIED, MetadataUtils.getSourceFormat(metadata));

        metadata.put("sourceFormat", "xml");
        assertEquals(DataFormat.XML, MetadataUtils.getSourceFormat(metadata));

        metadata.put("sourceFormat", "foo");
        assertThrows(
                IllegalArgumentException.class,
                () -> MetadataUtils.getSourceFormat(metadata)
        );
    }

    @Test
    void testGetSourceType() {
        Metadata metadata = new Metadata();
        assertEquals(SourceType.UNSPECIFIED, MetadataUtils.getSourceType(metadata));

        metadata.put("sourceType", "schema");
        assertEquals(SourceType.SCHEMA, MetadataUtils.getSourceType(metadata));

        metadata.put("sourceType", "foo");
        assertThrows(
                IllegalArgumentException.class,
                () -> MetadataUtils.getSourceType(metadata)
        );
    }

    @Test
    void testGetXmlNamespaces() {
        Metadata metadata = new Metadata();
        assertTrue(MetadataUtils.getXmlNamespaces(metadata).isEmpty());

        metadata.put("xmlNamespaces", List.of(Map.of("alias", "foo", "uri", "bar")));
        assertEquals(List.of(new XMLNamespace("foo", "bar")), new ArrayList<>(MetadataUtils.getXmlNamespaces(metadata)));
    }
}
