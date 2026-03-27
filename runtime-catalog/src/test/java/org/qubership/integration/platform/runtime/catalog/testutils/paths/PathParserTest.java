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

package org.qubership.integration.platform.runtime.catalog.testutils.paths;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.util.paths.PathElement;
import org.qubership.integration.platform.runtime.catalog.util.paths.PathParser;
import org.qubership.integration.platform.runtime.catalog.util.paths.PathPatternCharacters;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathParserTest {
    private PathParser parser;

    @BeforeEach
    void setUp() {
        parser = new PathParser();
    }

    @Test
    void testParse() {
        assertTrue(parser.parse("").isEmpty());
        List<PathElement> expected = Stream.of("foo", "bar", "bazz")
                .map(PathElement::new)
                .collect(Collectors.toList());
        assertEquals(expected, parser.parse("foo/bar/bazz"));
    }

    @Test
    void testParseElement() {
        assertTrue(parser.parseElement("").getPattern().isEmpty());
        assertEquals("foo", parser.parseElement("foo").getPattern());
        assertEquals("foo-" + PathPatternCharacters.PLACEHOLDER + "-baz",
                parser.parseElement("foo-{bar}-baz").getPattern());
    }
}
