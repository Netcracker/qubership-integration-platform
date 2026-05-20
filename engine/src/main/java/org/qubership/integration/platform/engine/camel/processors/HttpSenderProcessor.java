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

package org.qubership.integration.platform.engine.camel.processors;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.util.UnsafeUriCharactersEncoder;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class HttpSenderProcessor implements Processor {
    private static final String DEFAULT_PROTOCOL = "http";

    @Override
    public void process(Exchange exchange) throws Exception {
        Message message = exchange.getMessage();
        String url = message.getHeader(Headers.HTTP_URI, String.class);
        URI uri = new URI(UnsafeUriCharactersEncoder.encode(url));
        url = uri.toASCIIString();
        message.setHeader(Headers.HTTP_URI, fixUrlProtocol(url));
    }

    private String fixUrlProtocol(String url) {
        return !url.startsWith("http://") && !url.startsWith("https://")
                ? (DEFAULT_PROTOCOL + "://" + url) : url;
    }
}
