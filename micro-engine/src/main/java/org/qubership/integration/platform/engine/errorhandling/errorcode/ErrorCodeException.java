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

package org.qubership.integration.platform.engine.errorhandling.errorcode;


import lombok.Getter;
import org.qubership.integration.platform.engine.logging.constants.ContextHeaders;
import org.qubership.integration.platform.engine.model.errorhandling.ErrorEntry;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
public class ErrorCodeException extends RuntimeException {
    private final ErrorCode errorCode;
    private final String compiledMessage;
    private final String[] messageParams;
    private final Map<String, String> additionalExtraParams;

    public ErrorCodeException(ErrorCode errorCode, String ...messageParams) {
        this(errorCode, null, Collections.emptyMap(), messageParams);
    }

    public ErrorCodeException(ErrorCode errorCode, Map<String, String> additionalExtraParams) {
        this(errorCode, null, additionalExtraParams);
    }

    ErrorCodeException(ErrorCode errorCode, Throwable cause, Map<String, String> additionalExtraParams, String ...messageParams) {
        super(cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("ErrorCode cannot be null");
        } else {
            this.errorCode = errorCode;
            this.compiledMessage = this.errorCode.compileMessage(messageParams);
            this.messageParams = messageParams;
            this.additionalExtraParams = additionalExtraParams;
        }
    }

    @Override
    public String getMessage() {
        return String.format("[%s] %s",
            this.errorCode.getFormattedCode(),
            compiledMessage != null
                    ? compiledMessage
                    : this.errorCode.getPayload().getReason());
    }

    public ErrorEntry buildResponseObject() {
        Map<String, String> extraParams = buildParametersMapping();
        extraParams.putAll(additionalExtraParams);
        ErrorEntry err = ErrorEntry.builder()
            .code(errorCode.getFormattedCode())
            .message(compiledMessage)
            .reason(getErrorCode().getPayload().getReason())
            .extra(extraParams)
            .build();
        err.getExtra().remove(ContextHeaders.REQUEST_ID);
        return err;
    }

    private Map<String, String> buildParametersMapping() {
        List<String> extraKeys = errorCode.getPayload().getExtraKeys();
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < extraKeys.size(); i++) {
            if (messageParams.length > i && !"null".equals(messageParams[i])) {
                map.put(extraKeys.get(i), messageParams[i]);
            }
        }
        return map;
    }
}
