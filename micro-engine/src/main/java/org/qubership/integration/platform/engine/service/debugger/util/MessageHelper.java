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

package org.qubership.integration.platform.engine.service.debugger.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.StreamCache;
import org.apache.camel.WrappedFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class MessageHelper {

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    public static String extractBody(Exchange exchange) {
        Object body = exchange.getMessage().getBody();

        if (body == null) {
            return null;
        }

        if (body instanceof WrappedFile || body instanceof File) {
            return "[Body is file based: " + body + "]";
        }

        // is the body a stream cache or input stream
        StreamCache sc = null;
        InputStream is = null;
        if (body instanceof StreamCache) {
            sc = (StreamCache) body;
            is = null;
            resetCache(sc, is);
        } else if (body instanceof InputStream) {
            sc = null;
            is = (InputStream) body;
            resetCache(sc, is);
            // InputStream is closed unexpectedly when using camel type converter stream -> string, let's read manually
            try {
                body = is.readAllBytes();
            } catch (IOException ignored) { }
        }

        // Grab the message body as a string
        String stringBody = null;
        try {
            stringBody = exchange.getContext().getTypeConverter().tryConvertTo(String.class, exchange, body);
        } catch (Throwable ignored) { }
        if (stringBody == null) {
            try {
                stringBody = body.toString();
            } catch (Throwable ignored) { }
        }

        // Reset stream cache after use
        resetCache(sc, is);

        return stringBody;
    }

    @SuppressWarnings("checkstyle:EmptyCatchBlock")
    private static void resetCache(StreamCache sc, InputStream is) {
        if (sc != null) {
            try {
                sc.reset();
            } catch (Throwable ignored) { }
        }
        if (is != null) {
            try {
                is.reset();
            } catch (Throwable ignored) { }
        }
    }
}
