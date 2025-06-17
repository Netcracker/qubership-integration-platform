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

import org.qubership.integration.platform.runtime.catalog.exception.exceptions.SpecificationImportException;
import org.qubership.integration.platform.runtime.catalog.model.system.WsdlVersion;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

@Service
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class WsdlVersionParser {
    public static final String INVALID_WSDL_FILE_EXCEPTION = "Error during parsing WSDL structure: ";
    public static final String VERSION_PARSER_CONFIGURE_ERROR_MESSAGE = "Error during version's parser configure: ";

    private final SAXParserFactory saxParserFactory;

    @Autowired
    public WsdlVersionParser(
        @Qualifier("wsdlVersionSaxParserFactory") SAXParserFactory saxParserFactory
    ) {
        this.saxParserFactory = saxParserFactory;
    }

    public WsdlVersion getWSDLVersion(String documentText) {
        try {
            InputSource specificationInputSource = new InputSource(new StringReader(documentText));
            SAXParser parser = this.saxParserFactory.newSAXParser();
            WsdlVersionParserHandler wsdlVersionParserHandler = new WsdlVersionParserHandler();
            parser.parse(specificationInputSource, wsdlVersionParserHandler);
            return wsdlVersionParserHandler.getVersion();
        } catch (SAXException | IOException e) {
            throw new SpecificationImportException(INVALID_WSDL_FILE_EXCEPTION, e.getCause());
        } catch (ParserConfigurationException e) {
            throw new SpecificationImportException(VERSION_PARSER_CONFIGURE_ERROR_MESSAGE, e.getCause());
        }
    }
}
