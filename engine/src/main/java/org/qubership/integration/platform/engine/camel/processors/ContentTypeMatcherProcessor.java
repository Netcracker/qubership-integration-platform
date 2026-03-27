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

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Processor perform value matching of Content-Type header from response to configured value of response schema validation.
 * Received Content-Type header value should have the same directives as configured in Validation step, but may have different order.
 * <ul>
 * Example:
 * <li>"application/json;model=individual;version=v1;charset=UTF-8" matched "application/json;charset=UTF-8;version=v1;model=individual"</li>
 * <li>"application/json;model=individual;version=v1;charset=UTF-8" not matched "application/json;charset=UTF-8" or "application/json"</li>
 * </ul>
 */
@Slf4j
@Component
public class ContentTypeMatcherProcessor implements Processor {

    private static final String CONTENT_TYPE_DOES_NOT_MATCH_ERROR_MESSAGE = "Expected content type {} does not match actual content type {}. ";
    private static final String DIFFERENT_LENGTH = CONTENT_TYPE_DOES_NOT_MATCH_ERROR_MESSAGE + "Reason: Different length.";
    private static final String DIFFERENT_TYPE = CONTENT_TYPE_DOES_NOT_MATCH_ERROR_MESSAGE + "Reason: Different MIME types.";
    private static final String DIFFERENT_DIRECTIVES = CONTENT_TYPE_DOES_NOT_MATCH_ERROR_MESSAGE + "Reason: Different secondary directives.";
    private static final String CONTENT_TYPE_ANY = "*/*";

    @Override
    public void process(Exchange exchange) throws Exception {
        String expectedContentType = exchange.getProperty(CamelConstants.Properties.EXPECTED_CONTENT_TYPE, String.class);
        String actualContentType = exchange.getMessage().getHeader(Exchange.CONTENT_TYPE, String.class);

        exchange.setProperty(CamelConstants.Properties.MATCHED_CONTENT_TYPES, isMatched(expectedContentType, actualContentType));
    }

    private boolean isMatched(String expectedContentType, String actualContentType) {
        boolean contentTypeMatched = false;

        if (expectedContentType.equals(CONTENT_TYPE_ANY)) {
            return true;
        }

        if (actualContentType == null) {
            log.error(DIFFERENT_TYPE, expectedContentType, actualContentType);
            return contentTypeMatched;
        }

        if (expectedContentType.length() != actualContentType.length()) {
            log.error(DIFFERENT_LENGTH, expectedContentType, actualContentType);
            return contentTypeMatched;
        }

        String[] expectedContentTypesComponents = expectedContentType.split(";");
        String[] actualContentTypesComponents = actualContentType.split(";");

        if (!expectedContentTypesComponents[0].equals(actualContentTypesComponents[0])) {
            log.error(DIFFERENT_TYPE, expectedContentType, actualContentType);
            return contentTypeMatched;
        }

        List<String> intersectionList = new ArrayList<>(Arrays.asList(expectedContentTypesComponents));
        intersectionList.retainAll(Arrays.asList(actualContentTypesComponents));

        contentTypeMatched = intersectionList.size() == expectedContentTypesComponents.length;

        if (!contentTypeMatched) {
            log.error(DIFFERENT_DIRECTIVES, expectedContentType, actualContentType);
        }

        return contentTypeMatched;
    }
}
