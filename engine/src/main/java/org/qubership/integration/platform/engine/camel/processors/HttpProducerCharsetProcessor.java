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
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Processor;
import org.apache.camel.component.http.HttpProducer;
import org.apache.camel.support.ExchangeHelper;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.entity.ContentType;
import org.springframework.stereotype.Component;

import java.nio.charset.Charset;

/**
 * Allows replacing incorrect default request encoding in a component {@link HttpProducer}
 */
@Component
public class HttpProducerCharsetProcessor implements Processor {
    private static final String DEFAULT_REQUEST_CHARSET = "UTF-8";

    /**
     * See {@link HttpProducer#createRequestEntity(Exchange)}
     */
    @Override
    public void process(Exchange exchange) throws Exception {
        if (exchange != null) {
            // check charset in header/property
            if (StringUtils.isNotEmpty(ExchangeHelper.getCharsetName(exchange, false))) {
                return;
            }

            // check charset in content type
            String contentTypeString = ExchangeHelper.getContentType(exchange);
            ContentType contentType = null;
            if (contentTypeString != null) {
                // using ContentType.parser for charset
                if (contentTypeString.indexOf("charset") > 0 || contentTypeString.indexOf(';') > 0) {
                    contentType = ContentType.parse(contentTypeString);
                } else {
                    contentType = ContentType.create(contentTypeString);
                }
            }

            if (contentType != null) {
                // try to get the charset from the content-type
                Charset cs = contentType.getCharset();
                if (cs != null && StringUtils.isNotEmpty(cs.name())) {
                    return;
                }
            }

            // if no charset provided - set default value
            exchange.setProperty(ExchangePropertyKey.CHARSET_NAME, DEFAULT_REQUEST_CHARSET);
        }
    }
}
