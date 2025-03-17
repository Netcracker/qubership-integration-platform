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

import com.github.jknack.handlebars.Options;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.element.ContainerChainElement;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ParentIdentifierHelperTest {

    private static Options options;

    private final ParentIdentifierHelper helper = new ParentIdentifierHelper();


    @BeforeAll
    public static void initializeBeforeAll() {
        options = mock(Options.class);
        when(options.isFalsy(any())).thenReturn(false);
    }

    @DisplayName("Extracting parent identifier")
    @Test
    public void parentIdentifierTest() {
        ContainerChainElement parentElement = new ContainerChainElement();
        ChainElement testData = new ChainElement();
        parentElement.addChildElement(testData);

        Object actual = helper.apply(testData, options);

        assertThat(actual, equalTo(parentElement.getId()));
    }

    @DisplayName("Extracting null parent identifier")
    @Test
    public void emptyParentIdentifierTest() {
        ChainElement testData = new ChainElement();

        Object actual = helper.apply(testData, options);

        assertThat(actual, equalTo(StringUtils.EMPTY));
    }
}
