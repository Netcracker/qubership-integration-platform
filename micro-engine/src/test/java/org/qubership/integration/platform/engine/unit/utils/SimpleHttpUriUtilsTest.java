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

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.SimpleHttpUriUtils;

import java.net.MalformedURLException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class SimpleHttpUriUtilsTest {

    @Test
    void shouldReturnSameUriWhenHttpSchemePresent() throws MalformedURLException {
        String in = "http://example.com/path";
        Assertions.assertEquals(in, SimpleHttpUriUtils.formatUri(in));
    }

    @Test
    void shouldReturnSameUriWhenHttpsAndPortPresent() throws MalformedURLException {
        String in = "https://example.com:8080/api";
        assertEquals(in, SimpleHttpUriUtils.formatUri(in));
    }

    @Test
    void shouldAddHttpsSchemeWhenHostWithoutScheme() throws MalformedURLException {
        assertEquals("https://example.com", SimpleHttpUriUtils.formatUri("example.com"));
    }

    @Test
    void shouldAddHttpsSchemeWhenHostAndPathWithoutScheme() throws MalformedURLException {
        assertEquals("https://example.com/path", SimpleHttpUriUtils.formatUri("example.com/path"));
    }

    @Test
    void shouldAddHttpsSchemeWhenHostWithPortAndPath() throws MalformedURLException {
        assertEquals("https://localhost:8080/api", SimpleHttpUriUtils.formatUri("localhost:8080/api"));
    }

    @Test
    void shouldAddHttpsSchemeWhenIpv4WithoutScheme() throws MalformedURLException {
        assertEquals("https://127.0.0.1", SimpleHttpUriUtils.formatUri("127.0.0.1"));
    }

    @Test
    void shouldThrowWhenInputIsEmpty() {
        assertThrows(MalformedURLException.class, () -> SimpleHttpUriUtils.formatUri(""));
    }

    @Test
    void shouldThrowWhenInputIsRelativePath() {
        assertThrows(MalformedURLException.class, () -> SimpleHttpUriUtils.formatUri("/relative/path"));
    }

    @Test
    void shouldReturnNullWhenInputIsNull() throws MalformedURLException {
        assertNull(SimpleHttpUriUtils.formatUri(null));
    }

    @Test
    void shouldBeIdempotentAfterFirstFormatting() throws MalformedURLException {
        String once = SimpleHttpUriUtils.formatUri("example.com/path");
        String twice = SimpleHttpUriUtils.formatUri(once);
        assertEquals(once, twice);
    }
}
