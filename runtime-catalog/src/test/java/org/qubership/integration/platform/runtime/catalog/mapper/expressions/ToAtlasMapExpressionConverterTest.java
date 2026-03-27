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

package org.qubership.integration.platform.runtime.catalog.mapper.expressions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToAtlasMapExpressionConverterTest {
    private static final ToAtlasMapExpressionConverter CONVERTER = new ToAtlasMapExpressionConverter();

    @Test
    public void testConvert() {
        String result = CONVERTER.convert(
                "foo() || bar(body.property.header, constant.hello\\ world)",
                reference -> reference.kind().name() + ":" + String.join("/", reference.path())
        );
        assertEquals("foo() || bar(${BODY:property/header}, ${CONSTANT:hello world})", result);
    }

    @Test
    public void testConvertConstantWithIntegerNumberAsName() {
        String result = CONVERTER.convert("constant.42",
                reference -> reference.kind().name() + ":" + String.join("/", reference.path()));
        assertEquals("${CONSTANT:42}", result);
    }
}
