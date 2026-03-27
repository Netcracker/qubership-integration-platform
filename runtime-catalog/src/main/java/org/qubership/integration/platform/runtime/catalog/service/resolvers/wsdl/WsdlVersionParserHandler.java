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

import lombok.Getter;
import org.qubership.integration.platform.runtime.catalog.model.system.WsdlVersion;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

@Getter
public class WsdlVersionParserHandler extends DefaultHandler {

    private WsdlVersion version = WsdlVersion.WSDL_1;
    private static final String DESCRIPTION_TAG_NAME = "description";

    @Override
    public void startDocument() {
        this.version = WsdlVersion.WSDL_1;
    }

    @Override
    public void startElement(String uri, String lName, String qName, Attributes attr) {
        if (qName.contains(DESCRIPTION_TAG_NAME)) {
            this.version = WsdlVersion.WSDL_2;
        }
    }

}
