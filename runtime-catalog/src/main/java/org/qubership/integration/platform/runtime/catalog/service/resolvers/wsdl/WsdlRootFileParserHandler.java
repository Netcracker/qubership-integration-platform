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

package org.qubership.integration.platform.runtime.catalog.service.resolvers.wsdl;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.util.Set;

public class WsdlRootFileParserHandler extends DefaultHandler {

    private static final Set<String> WSDL_NAMESPACES = Set.of(
            "http://schemas.xmlsoap.org/wsdl/", // WSDL 1.1
            "http://www.w3.org/ns/wsdl"         // WSDL 2.0
    );

    private boolean hasService = false;
    private boolean hasBinding = false;

    @Override
    public void startElement(String uri, String lName, String qName, Attributes attr) {
        if (WSDL_NAMESPACES.contains(uri)) {
            if ("service".equals(lName)) {
                hasService = true;
            } else if ("binding".equals(lName)) {
                hasBinding = true;
            }
        }
    }

    public boolean isRootCandidate() {
        return hasService && hasBinding;
    }
}
