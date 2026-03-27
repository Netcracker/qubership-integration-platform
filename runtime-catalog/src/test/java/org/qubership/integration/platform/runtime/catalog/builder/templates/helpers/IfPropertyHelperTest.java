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

import com.github.jknack.handlebars.*;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.testutils.TestUtils;
import org.xmlunit.matchers.CompareMatcher;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.text.IsEmptyString.emptyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

@ExtendWith(MockitoExtension.class)
public class IfPropertyHelperTest {

    private static MockedStatic<UUID> mockedUUID;
    private static Handlebars hbs;

    private final IfPropertyHelper helper = new IfPropertyHelper();


    @BeforeAll
    public static void initializeBeforeAll() {
        UUID uuid = UUID.fromString("6574764f-49a0-42e2-a495-7a98cbc99c1d");
        mockedUUID = mockStatic(UUID.class, CALLS_REAL_METHODS);
        mockedUUID.when(UUID::randomUUID).thenReturn(uuid);
        hbs = new Handlebars()
                .with(new ClassPathTemplateLoader("/testData/input/builder/templates/helpers/templates", "/template.hbs"))
                .with(EscapingStrategy.NOOP);
        hbs.setInfiniteLoops(true);
        hbs.setPrettyPrint(true);
    }

    @AfterAll
    public static void finalizeAfterAll() {
        mockedUUID.close();
    }

    private static Stream<Arguments> ifPropertyTrueTestData() {
        return Stream.of(
                Arguments.of(
                        "Equals operation",
                        "server",
                        Pair.of("equals", "web"),
                        Collections.singletonMap("server", "web")
                ),
                Arguments.of(
                        "Not Equals operation",
                        "server",
                        Pair.of("not-equals", "web"),
                        Collections.singletonMap("server", "application")
                ),
                Arguments.of(
                        "Presented operation",
                        "server",
                        Pair.of("presented", ""),
                        Collections.singletonMap("server", "web")
                ),
                Arguments.of(
                        "Not Presented operation",
                        "server",
                        Pair.of("not-presented", ""),
                        Collections.emptyMap()
                ),
                Arguments.of(
                        "Empty operation",
                        "server",
                        Pair.of("empty", ""),
                        Collections.singletonMap("server", "")
                ),
                Arguments.of(
                        "Not Empty operation",
                        "server",
                        Pair.of("not-empty", ""),
                        Collections.singletonMap("server", "application")
                ),
                Arguments.of(
                        "Contains operation",
                        "server",
                        Pair.of("contains", "web"),
                        Collections.singletonMap("server", "Test web server")
                ),
                Arguments.of(
                        "Not Contains operation",
                        "server",
                        Pair.of("not-contains", "application"),
                        Collections.singletonMap("server", "Test web server")
                ),
                Arguments.of(
                        "Contains Query Params operation",
                        "url",
                        Pair.of("contains-query-params", ""),
                        Collections.singletonMap("url", "http://locallhost:8080/api/v1/test?param=test")
                ),
                Arguments.of(
                        "Not Contains Query Params operation",
                        "url",
                        Pair.of("not-contains-query-params", ""),
                        Collections.singletonMap("url", "http://locallhost:8080/api/v1/test")
                ),
                Arguments.of(
                        "In operation",
                        "item",
                        Pair.of("in", "firstItem,secondItem,thirdItem"),
                        Collections.singletonMap("item", "secondItem")
                ),
                Arguments.of(
                        "Not In operation",
                        "item",
                        Pair.of("not-in", "secondItem,thirdItem"),
                        Collections.singletonMap("item", "firstItem")
                )
        );
    }

    private static Stream<Arguments> ifPropertyFalseTestData() {
        return Stream.of(
                Arguments.of(
                        "Equals operation",
                        "server",
                        Pair.of("equals", "web"),
                        Collections.singletonMap("server", "application")
                ),
                Arguments.of(
                        "Not Equals operation",
                        "server",
                        Pair.of("not-equals", "web"),
                        Collections.singletonMap("server", "web")
                ),
                Arguments.of(
                        "Presented operation",
                        "server",
                        Pair.of("presented", ""),
                        Collections.emptyMap()
                ),
                Arguments.of(
                        "Not Presented operation",
                        "server",
                        Pair.of("not-presented", ""),
                        Collections.singletonMap("server", "application")
                ),
                Arguments.of(
                        "Empty operation",
                        "server",
                        Pair.of("empty", ""),
                        Collections.singletonMap("server", "web")
                ),
                Arguments.of(
                        "Not Empty operation",
                        "server",
                        Pair.of("not-empty", ""),
                        Collections.singletonMap("server", "")
                ),
                Arguments.of(
                        "Contains operation",
                        "server",
                        Pair.of("contains", "web"),
                        Collections.singletonMap("server", "Test application server")
                ),
                Arguments.of(
                        "Not Contains operation",
                        "server",
                        Pair.of("not-contains", "application"),
                        Collections.singletonMap("server", "Test application server")
                ),
                Arguments.of(
                        "Contains Query Params operation",
                        "url",
                        Pair.of("contains-query-params", ""),
                        Collections.singletonMap("url", "http://locallhost:8080/api/v1/test")
                ),
                Arguments.of(
                        "Not Contains Query Params operation",
                        "url",
                        Pair.of("not-contains-query-params", ""),
                        Collections.singletonMap("url", "http://locallhost:8080/api/v1/test?param=test")
                ),
                Arguments.of(
                        "In operation",
                        "item",
                        Pair.of("in", "firstItem,thirdItem"),
                        Collections.singletonMap("item", "secondItem")
                ),
                Arguments.of(
                        "Not In operation",
                        "item",
                        Pair.of("not-in", "firstItem,thirdItem"),
                        Collections.singletonMap("item", "firstItem")
                )
        );
    }

    @DisplayName("True case of applying conditions on properties")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("ifPropertyTrueTestData")
    public void ifPropertyTrueTest(
            String scenario,
            String propertyName,
            Pair<String, String> operation,
            Map<String, Object> properties
    ) throws IOException {
        String expected = TestUtils.getResourceFileContent(
                "/testData/output/builder/templates/helpers/if-property/split_async_result.xml");

        ChainElement chainElement = new ChainElement();
        chainElement.setProperties(properties);

        String actual;
        try (StringWriter stringWriter = new StringWriter()) {
            Options options = new Options.Builder(
                    hbs, "if-property", TagType.SECTION, Context.newContext(chainElement), hbs.compile("split-async"))
                    .setHash(Collections.singletonMap(operation.getKey(), operation.getValue()))
                    .setWriter(stringWriter)
                    .build();
            helper.apply(propertyName, options);
            actual = stringWriter.toString();
        }

        assertThat(actual, CompareMatcher.isIdenticalTo(expected).ignoreWhitespace());
    }

    @DisplayName("False case of applying conditions on properties")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("ifPropertyFalseTestData")
    public void ifPropertyFalseTest(
            String scenario,
            String propertyName,
            Pair<String, String> operation,
            Map<String, Object> properties
    ) throws IOException {
        ChainElement chainElement = new ChainElement();
        chainElement.setProperties(properties);

        String actual;
        try (StringWriter stringWriter = new StringWriter()) {
            Options options = new Options.Builder(
                    hbs, "if-property", TagType.SECTION, Context.newContext(chainElement), hbs.compile("split-async"))
                    .setHash(Collections.singletonMap(operation.getKey(), operation.getValue()))
                    .setWriter(stringWriter)
                    .build();
            helper.apply(propertyName, options);
            actual = stringWriter.toString();
        }

        assertThat(actual, is(emptyString()));
    }
}
