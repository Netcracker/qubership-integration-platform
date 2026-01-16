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

package org.qubership.integration.platform.engine.testutils;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Message;
import org.apache.camel.TypeConverter;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;


public final class MockExchanges {

    private MockExchanges() {
    }

    public static Exchange basic() {
        Exchange ex = mock(Exchange.class);

        ConcurrentMap<Object, Object> props = new ConcurrentHashMap<>();
        AtomicReference<Throwable> exceptionRef = new AtomicReference<>();

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            if (val == null) {
                props.remove(key);
            } else {
                props.put(key, val);
            }
            return null;
        }).when(ex).setProperty(anyString(), any());

        doAnswer(inv -> {
            ExchangePropertyKey key = inv.getArgument(0);
            Object val = inv.getArgument(1);
            String k = key.getName();
            if (val == null) {
                props.remove(k);
            } else {
                props.put(k, val);
            }
            return null;
        }).when(ex).setProperty(any(ExchangePropertyKey.class), any());

        when(ex.getProperty(anyString())).thenAnswer(inv -> props.get(inv.getArgument(0)));

        when(ex.getProperty(anyString(), any(Class.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Class<?> type = inv.getArgument(1);
            Object val = props.get(key);
            return val == null ? null : type.cast(val);
        });

        when(ex.getProperty(anyString(), any(), any(Class.class))).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            Object def = inv.getArgument(1);
            Class<?> type = inv.getArgument(2);
            Object val = props.get(key);
            return val == null ? def : type.cast(val);
        });

        when(ex.getProperty(any(ExchangePropertyKey.class), any(Class.class))).thenAnswer(inv -> {
            ExchangePropertyKey key = inv.getArgument(0);
            Class<?> type = inv.getArgument(1);
            Object val = props.get(key.getName());
            return val == null ? null : type.cast(val);
        });

        when(ex.getProperty(any(ExchangePropertyKey.class), any(), any(Class.class))).thenAnswer(inv -> {
            ExchangePropertyKey key = inv.getArgument(0);
            Object def = inv.getArgument(1);
            Class<?> type = inv.getArgument(2);
            Object val = props.get(key.getName());
            return val == null ? def : type.cast(val);
        });

        doAnswer(inv -> {
            exceptionRef.set(inv.getArgument(0));
            return null;
        })
                .when(ex).setException(any());
        when(ex.getException()).thenAnswer(inv -> exceptionRef.get());

        return ex;
    }

    public static Exchange withMessage() {
        Exchange ex = basic();
        Message msg = mock(Message.class);
        when(ex.getMessage()).thenReturn(msg);
        when(ex.getIn()).thenReturn(msg);
        when(msg.getExchange()).thenReturn(ex);
        return ex;
    }

    public static Exchange withMessageAndConverter() {
        Exchange ex = withMessage();
        CamelContext ctx = mock(CamelContext.class);
        TypeConverter tc = mock(TypeConverter.class);
        when(ex.getContext()).thenReturn(ctx);
        when(ctx.getTypeConverter()).thenReturn(tc);
        return ex;
    }

    public static TypeConverter getTypeConverter(Exchange ex) {
        CamelContext ctx = ex.getContext();
        return ctx != null ? ctx.getTypeConverter() : null;
    }

    public static Message getMessage(Exchange ex) {
        return ex.getMessage();
    }
}
