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

package org.qubership.integration.platform.runtime.catalog.builder.templates.helpers;

import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.TagType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PropertyHelperSourceTest {

    private final PropertyHelperSource helper = new PropertyHelperSource();


    @DisplayName("Extracting property")
    @Test
    public void propertyTest() {
        String propertyKey = "testProperty";
        String propertyValue = "test12345";
        ChainElement element = new ChainElement();
        element.setProperties(Collections.singletonMap(propertyKey, propertyValue));

        Options options = new Options.Builder(null, null, TagType.VAR, Context.newContext(element), null)
                .build();

        String actual = String.valueOf(helper.property(propertyKey, options));

        assertThat(actual, equalTo(propertyValue));
    }

    @DisplayName("Error case of extracting property")
    @Test
    public void propertyErrorTest() {
        assertThrows(NullPointerException.class, () -> helper.property(null, null));
    }

    @DisplayName("Wrapping cdata")
    @Test
    public void cdataTest() {
        String expected = """
                <![CDATA[
                testData
                ]]>""";

        String actual = String.valueOf(helper.cdata("testData"));

        assertThat(actual, equalTo(expected));
    }

    @DisplayName("Wrapping null cdata")
    @Test
    public void nullCdataTest() {
        assertThat(helper.cdata(null), is(nullValue()));
    }
}
