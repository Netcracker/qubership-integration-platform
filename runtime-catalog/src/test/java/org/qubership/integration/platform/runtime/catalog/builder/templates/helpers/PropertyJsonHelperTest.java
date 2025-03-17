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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.github.jknack.handlebars.Context;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.TagType;
import org.json.JSONException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class PropertyJsonHelperTest {

    private static final String HELPER_NAME = "property-json";
    private static final String PROPERTY_KEY = "orderBody";

    private final PropertyJsonHelper helper = new PropertyJsonHelper();


    @DisplayName("Extracting json property")
    @Test
    public void jsonPropertyTest() throws JsonProcessingException, JSONException {
        String expected = """
                {
                    "order": {
                        "id": "123456",
                        "items": [
                            {
                                "id": 1
                            },
                            {
                                "id": 2
                            }
                        ]
                    }
                }""";

        ChainElement element = new ChainElement();
        element.setProperties(Collections.singletonMap(
                PROPERTY_KEY,
                TestUtils.OBJECT_MAPPER.readValue(expected, new TypeReference<>() {})
        ));
        Options options = new Options
                .Builder(null, HELPER_NAME, TagType.VAR, Context.newContext(element), null)
                .build();

        String actual = helper.apply(PROPERTY_KEY, options);

        JSONAssert.assertEquals(expected, actual, JSONCompareMode.NON_EXTENSIBLE);
    }

    @DisplayName("Extracting null json property")
    @Test
    public void nullJsonPropertyTest() {
        ChainElement element = new ChainElement();
        element.setProperties(Collections.singletonMap(PROPERTY_KEY, null));
        Options options = new Options
                .Builder(null, HELPER_NAME, TagType.VAR, Context.newContext(element), null)
                .build();

        String actual = helper.apply(PROPERTY_KEY, options);

        assertThat(actual, is(nullValue()));
    }
}
