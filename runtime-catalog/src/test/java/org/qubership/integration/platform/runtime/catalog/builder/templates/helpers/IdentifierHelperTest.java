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

import java.io.IOException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

public class IdentifierHelperTest {

    private final IdentifierHelper helper = new IdentifierHelper();


    private Options instantiateOptions(Context context) {
        return new Options.Builder(null, "identifier", TagType.VAR, context, null).build();
    }

    @DisplayName("Extracting chain element identifier")
    @Test
    public void elementIdentifierTest() throws IOException {
        ChainElement testData = new ChainElement();

        Object actual = helper.apply(testData, instantiateOptions(null));

        assertThat(actual, equalTo(testData.getId()));
    }

    @DisplayName("Extracting context element identifier")
    @Test
    public void contextElementIdentifierTest() throws IOException {
        ChainElement testData = new ChainElement();
        Context context = Context.newContext(Context.newContext(testData), null);

        Object actual = helper.apply(new Object(), instantiateOptions(context));

        assertThat(actual, equalTo(testData.getId()));
    }

    @DisplayName("Extracting null element identifier")
    @Test
    public void nullElementIdentifierTest() throws IOException {
        Object actual = helper.apply(null, instantiateOptions(Context.newContext(null)));

        assertThat(actual, equalTo(null));
    }
}
