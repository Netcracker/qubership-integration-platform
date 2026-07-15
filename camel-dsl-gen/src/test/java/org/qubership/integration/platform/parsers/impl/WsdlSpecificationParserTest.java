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

package org.qubership.integration.platform.parsers.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.parsers.SpecificationSource;
import org.qubership.integration.platform.parsers.model.ParsedOperation;
import org.qubership.integration.platform.parsers.model.wsdl.WsdlEndpoint;
import org.qubership.integration.platform.parsers.model.wsdl.WsdlParseResult;
import org.qubership.integration.platform.parsers.resolvers.wsdl.WsdlVersionParser;

import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsdlSpecificationParserTest {

    private static final String HELLO_WSDL = """
            <?xml version="1.0" encoding="UTF-8"?>
            <definitions xmlns="http://schemas.xmlsoap.org/wsdl/"
                         xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
                         xmlns:tns="http://example.com/hello"
                         xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                         targetNamespace="http://example.com/hello"
                         name="HelloService">
              <message name="sayHelloRequest">
                <part name="firstName" type="xsd:string"/>
              </message>
              <message name="sayHelloResponse">
                <part name="greeting" type="xsd:string"/>
              </message>
              <portType name="HelloPortType">
                <operation name="sayHello">
                  <input message="tns:sayHelloRequest"/>
                  <output message="tns:sayHelloResponse"/>
                </operation>
              </portType>
              <binding name="HelloBinding" type="tns:HelloPortType">
                <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>
                <operation name="sayHello">
                  <soap:operation soapAction="sayHello"/>
                  <input><soap:body use="literal"/></input>
                  <output><soap:body use="literal"/></output>
                </operation>
              </binding>
              <service name="HelloService">
                <port name="HelloPort" binding="tns:HelloBinding">
                  <soap:address location="http://example.com/hello"/>
                </port>
              </service>
            </definitions>
            """;

    private WsdlSpecificationParser parser;

    @BeforeEach
    void setUp() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
        factory.setValidating(false);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        parser = new WsdlSpecificationParser(new WsdlVersionParser(factory));
    }

    @Test
    void extractsOperationsFromWsdlV1() {
        SpecificationSource mainSource = new SpecificationSource("hello.wsdl", HELLO_WSDL);

        WsdlParseResult result = parser.parse(List.of(mainSource), mainSource);

        List<ParsedOperation> operations = result.systemModel().getOperations();
        assertEquals(1, operations.size());
        assertEquals("sayHello", operations.get(0).getName());
        assertEquals("POST", operations.get(0).getMethod());
    }

    @Test
    void surfacesServiceEndpointsForEnvironmentResolution() {
        SpecificationSource mainSource = new SpecificationSource("hello.wsdl", HELLO_WSDL);

        WsdlParseResult result = parser.parse(List.of(mainSource), mainSource);

        assertEquals(1, result.endpoints().size());
        WsdlEndpoint endpoint = result.endpoints().get(0);
        assertEquals("HelloPort", endpoint.name());
        assertEquals("http://example.com/hello", endpoint.address());
    }

    @Test
    void leavesEndpointAddressUnvalidated() {
        SpecificationSource mainSource = new SpecificationSource("hello.wsdl", HELLO_WSDL);

        WsdlParseResult result = parser.parse(List.of(mainSource), mainSource);

        assertTrue(result.endpoints().stream().allMatch(endpoint -> endpoint.address() != null));
    }
}
