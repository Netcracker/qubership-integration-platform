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
import org.qubership.integration.platform.runtime.catalog.util.paths.PathIntersectionChecker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PathIntersectionCheckerTest {
    private PathIntersectionChecker intersectionChecker;

    @BeforeEach
    void setUp() {
        intersectionChecker = new PathIntersectionChecker();
    }

    @Test
    void testIntersection() {
        assertFalse(intersectionChecker.intersects("/foo", "/foo/bar"));
        assertTrue(intersectionChecker.intersects("/foo/bar", "foo/bar"));
        assertFalse(intersectionChecker.intersects("/foo/bar", "/foo/{bar}"));
        assertTrue(intersectionChecker.intersects("/foo/{baz}", "/foo/{bar}"));
        assertFalse(intersectionChecker.intersects("/foo/{bar}", "/foo/bar-{baz}"));
        assertFalse(intersectionChecker.intersects("/foo/bar", "/foo/bar-{baz}"));
        assertFalse(intersectionChecker.intersects("{foo}-bar", "{foo}-bz"));
    }
}
