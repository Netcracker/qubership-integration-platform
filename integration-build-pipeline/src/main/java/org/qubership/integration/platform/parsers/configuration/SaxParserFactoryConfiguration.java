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

package org.qubership.integration.platform.parsers.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/**
 * Provides the hardened SAX parser factory that the WSDL parsers use to read specification sources.
 *
 * <p>The factory is namespace-aware (WSDL elements are matched by namespace URI) and locked down
 * against XML external-entity attacks. It is exposed as a library auto-configuration so a consumer
 * that only pulls in this library still gets working WSDL parsing; an application that already
 * defines a {@code wsdlVersionSaxParserFactory} bean keeps its own.
 */
@AutoConfiguration
public class SaxParserFactoryConfiguration {
    @Bean("wsdlVersionSaxParserFactory")
    @ConditionalOnMissingBean(name = "wsdlVersionSaxParserFactory")
    public SAXParserFactory wsdlVersionSaxParserFactory()
            throws ParserConfigurationException, SAXNotRecognizedException, SAXNotSupportedException {
        SAXParserFactory factory = SAXParserFactory.newDefaultInstance();
        factory.setValidating(false);
        // WsdlRootFileParser matches WSDL elements by namespace URI, which SAX reports only in
        // namespace-aware mode. Keep this enabled.
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory;
    }
}
