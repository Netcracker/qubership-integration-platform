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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;

import java.util.Collections;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.text.IsEmptyString.emptyString;

public class ManualQueryParamsHelperTest {

    private final ManualQueryParamsHelper helper = new ManualQueryParamsHelper(TestUtils.OBJECT_MAPPER);


    @DisplayName("Composing manual query params")
    @Test
    public void manualQueryParamsTest() throws JsonProcessingException {
        String expected = "?brokers=kafka-main-1:9092,kafka-main-2:9092&topic=test-topic&groupId=test-topic-group&batchSize=20";

        Map<String, Object> testData = TestUtils.OBJECT_MAPPER.readValue(
                """
                           {
                              "brokers": "kafka-main-1:9092,kafka-main-2:9092",
                              "jsonQueryParameters": "{\\"topic\\":\\"test-topic\\",\\"groupId\\":\\"test-topic-group\\"}",
                              "batchSize": "20",
                              "consumerResetOffset": "",
                              "pollInterval": null
                           }
                        """,
                new TypeReference<>() {}
        );

        Options options = new Options.Builder(null, "manualQueryParams", TagType.VAR, Context.newContext(null), null)
                .setHash(testData)
                .build();

        String actual = helper.manualQuery(options).toString();

        assertThat(actual, equalTo(expected));
    }

    @DisplayName("Composing empty manual query params")
    @Test
    public void emptyManualQueryParamsTest() {
        Options options = new Options.Builder(null, "manualQueryParams", TagType.VAR, Context.newContext(null), null)
                .setHash(Collections.emptyMap())
                .build();

        String actual = helper.manualQuery(options).toString();

        assertThat(actual, is(emptyString()));
    }
}
