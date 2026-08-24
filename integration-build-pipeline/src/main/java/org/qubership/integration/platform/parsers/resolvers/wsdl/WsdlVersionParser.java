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

package org.qubership.integration.platform.parsers.resolvers.wsdl;

import org.qubership.integration.platform.parsers.SpecificationParserException;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public class WsdlVersionParser {
    public static final String INVALID_WSDL_FILE_EXCEPTION = "Error during parsing WSDL structure: ";
    public static final String VERSION_PARSER_CONFIGURE_ERROR_MESSAGE = "Error during version's parser configure: ";

    private final SAXParserFactory saxParserFactory;

    public WsdlVersionParser(SAXParserFactory saxParserFactory) {
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
            throw new SpecificationParserException(INVALID_WSDL_FILE_EXCEPTION, e.getCause());
        } catch (ParserConfigurationException e) {
            throw new SpecificationParserException(VERSION_PARSER_CONFIGURE_ERROR_MESSAGE, e.getCause());
        }
    }
}
