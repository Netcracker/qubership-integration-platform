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

package org.qubership.integration.platform.io.writers.camel.xml.templates.helpers;

import com.github.jknack.handlebars.Helper;
import com.github.jknack.handlebars.Options;
import org.qubership.integration.platform.chain.model.Element;
import org.qubership.integration.platform.io.writers.camel.xml.templates.TemplatesHelper;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handlebars helper, which is used, to receive element
 * from ContainerChainElement with particular name
 */
@TemplatesHelper("children")
public class ChildrenHelper extends BaseHelper implements Helper<String> {

    public static final String SORT_PROP = "comparison-key-property";

    public Object apply(String childrenName, Options options) throws IOException {
        Element container = (Element) options.context.model();
        if (container != null) {
            List<Element> children = findChildrenByType(
                    container,
                    childrenName,
                    (String) options.hash.get(SORT_PROP));
            return putCollectionAsContext(children, options);
        } else {
            return options.inverse();
        }
    }

    /**
     * Method which is used to find elements, that property NAME equals <code>name</code> argument,
     * in the <code>container</code> parameter.
     *
     * @param container Parent element, from which list of eleme
     * @param name      Name of camel elements
     * @return Children list from <code>container</code> container argument with property
     *      NAME equals <code>name</code> argument.
     */
    private List<Element> findChildrenByType(Element container, String name, String comparisonKeyProperty) {
        return container.getChildren().stream()
                .filter(nextChild -> name.equals(nextChild.getType()))
                .sorted(Comparator.comparing(
                        value -> {
                            try {
                                return Integer.parseInt(
                                        value.getProperties().getOrDefault(comparisonKeyProperty, "0").toString());
                            } catch (Exception e) {
                                return 0;
                            }
                        }))
                .collect(Collectors.toList());
    }

}
