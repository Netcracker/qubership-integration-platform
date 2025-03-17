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
import com.github.jknack.handlebars.helper.ConditionalHelpers;
import com.github.jknack.handlebars.helper.StringHelpers;
import com.github.jknack.handlebars.io.ClassPathTemplateLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.catalog.configuration.MapperBaseConfiguration;
import org.qubership.integration.platform.catalog.configuration.element.descriptor.DescriptorPropertiesConfiguration;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.service.library.LibraryElementsService;
import org.qubership.integration.platform.catalog.service.library.LibraryResourceLoader;
import org.qubership.integration.platform.runtime.catalog.builder.templates.TemplateService;
import org.qubership.integration.platform.runtime.catalog.mapper.MappingDescriptionValidator;
import org.qubership.integration.platform.runtime.catalog.mapper.atlasmap.AtlasMapInterpreter;
import org.qubership.integration.platform.runtime.catalog.testutils.configuration.TestConfig;
import org.qubership.integration.platform.runtime.catalog.testutils.dto.ChainImportDTO;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainElementsMapper;
import org.qubership.integration.platform.runtime.catalog.testutils.mapper.ChainMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.xmlunit.matchers.CompareMatcher;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Collections;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.qubership.integration.platform.runtime.catalog.builder.templates.helpers.ChildrenHelper.SORT_PROP;
import static org.qubership.integration.platform.runtime.catalog.testutils.TestUtils.YAML_MAPPER;
import static org.qubership.integration.platform.runtime.catalog.testutils.TestUtils.getResourceFileContent;

@ContextConfiguration(classes = {
        AtlasMapInterpreter.class,
        MapperBaseConfiguration.class,
        TestConfig.class,
        DescriptorPropertiesConfiguration.class,
        LibraryElementsService.class,
        TemplateService.class,
        LibraryResourceLoader.class,
        ChildrenHelper.class,
        ChainElementsMapper.class,
        ChainMapper.class,
        MappingDescriptionValidator.class
})
@ExtendWith(SpringExtension.class)
public class ChildrenHelperTest {

    private static Handlebars hbs;
    private static final ChildrenHelper childrenHelper = new ChildrenHelper();

    private final ChainMapper chainMapper;

    @Autowired
    public ChildrenHelperTest(ChainMapper chainMapper) {
        this.chainMapper = chainMapper;
    }

    @BeforeAll
    public static void initializeBeforeAll() {
        hbs = new Handlebars()
                .with(new ClassPathTemplateLoader("/testData/input/builder/templates/helpers/templates", "/template.hbs"))
                .with(EscapingStrategy.NOOP);
        hbs.setInfiniteLoops(true);
        hbs.setPrettyPrint(true);
        hbs.registerHelpers(StringHelpers.class);
        hbs.registerHelpers(ConditionalHelpers.class);
        hbs.registerHelper("children", childrenHelper);
    }

    private static Stream<Arguments> childrenTestData() {
        return Stream.of(
                Arguments.of(
                        "Split Async with 3 children",
                        "async-split-element",
                        "/testData/input/builder/templates/helpers/children/split_async.yml",
                        "/testData/output/builder/templates/helpers/children/split_async.xml"
                ),
                Arguments.of(
                        "Try-Catch-Finally with 3 catches with priorities",
                        "catch",
                        "/testData/input/builder/templates/helpers/children/try_catch.yml",
                        "/testData/output/builder/templates/helpers/children/try_catch.xml"
                ),
                Arguments.of(
                        "Split Async empty",
                        "async-split-element",
                        "/testData/input/builder/templates/helpers/children/split_async_empty.yml",
                        "/testData/output/builder/templates/helpers/children/split_async_empty.xml"
                )
        );
    }

    @DisplayName("Extracting children element")
    @ParameterizedTest(name = "#{index} => {0}")
    @MethodSource("childrenTestData")
    public void childrenTest(String scenario, String childName, String inputPath, String outputPath) throws IOException {
        String expected = getResourceFileContent(outputPath);

        ChainElement testData = chainMapper.toEntity(YAML_MAPPER.readValue(getResourceFileContent(inputPath), ChainImportDTO.class))
                .getElements().stream()
                .filter(it -> it.getParent() == null)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Incorrect test data"));

        String actual;
        try (StringWriter stringWriter = new StringWriter()) {
            Template template = hbs.compile(testData.getType());

            Context testContext = Context.newContext(testData);
            Options options = new Options.Builder(hbs, "children", TagType.SECTION, testContext, template)
                    .setWriter(stringWriter)
                    .setHash(Collections.singletonMap(SORT_PROP, "priorityNumber"))
                    .build();
            childrenHelper.apply(childName, options);
            actual = wrap(stringWriter.toString());
        }

        assertThat(actual, CompareMatcher.isIdenticalTo(expected).ignoreWhitespace());
    }

    private String wrap(String xml) {
        return new StringBuilder()
                .append("<route>\n")
                .append(xml)
                .append("</route>")
                .toString();
    }
}
