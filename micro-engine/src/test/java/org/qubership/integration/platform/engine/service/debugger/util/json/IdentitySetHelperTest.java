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

package org.qubership.integration.platform.engine.service.debugger.util.json;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class IdentitySetHelperTest {

    @Test
    void shouldUseIdentitySemanticsNotEquals() {
        Set<Object> set = IdentitySetHelper.createIdentitySet();
        String a1 = new String("a");
        String a2 = new String("a");
        assertNotSame(a1, a2);
        assertEquals(a1, a2);

        assertTrue(set.add(a1));
        assertFalse(set.contains(a2));
        assertTrue(set.add(a2));
        assertEquals(2, set.size());

        assertTrue(set.remove(a1));
        assertTrue(set.contains(a2));
        assertEquals(1, set.size());
    }

    @Test
    void shouldBehaveLikeSetForSameInstance() {
        Set<Object> set = IdentitySetHelper.createIdentitySet();
        Object o = new Object();

        assertTrue(set.add(o));
        assertFalse(set.add(o));
        assertTrue(set.contains(o));
        assertEquals(1, set.size());
    }

    @Test
    void shouldAllowNullElement() {
        Set<Object> set = IdentitySetHelper.createIdentitySet();

        assertTrue(set.add(null));
        assertFalse(set.add(null));
        assertTrue(set.contains(null));
        assertEquals(1, set.size());

        assertTrue(set.remove(null));
        assertFalse(set.contains(null));
        assertEquals(0, set.size());
    }
}
