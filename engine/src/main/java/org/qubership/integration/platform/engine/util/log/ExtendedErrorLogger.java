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

package org.qubership.integration.platform.engine.util.log;

import jakarta.annotation.Nullable;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

public class ExtendedErrorLogger implements Logger {
    public static final String ERROR_CODE_LOG_PREFIX_TEMPLATE = "[error_code=%s] ";
    private final Logger log;

    ExtendedErrorLogger(String name) {
        this.log = LoggerFactory.getLogger(name);
    }

    ExtendedErrorLogger(Class<?> clazz) {
        this.log = LoggerFactory.getLogger(clazz.getName());
    }

    public String getName() {
        return this.log.getName();
    }

    public boolean isTraceEnabled() {
        return this.log.isTraceEnabled();
    }

    public void trace(String msg) {
        this.log.trace(msg);
    }

    public void trace(String format, Object arg) {
        this.log.trace(format, arg);
    }

    public void trace(String format, Object arg1, Object arg2) {
        this.log.trace(format, arg1, arg2);
    }

    public void trace(String format, Object... arguments) {
        this.log.trace(format, arguments);
    }

    public void trace(String msg, Throwable t) {
        this.log.trace(msg, t);
    }

    public boolean isTraceEnabled(Marker marker) {
        return this.log.isTraceEnabled(marker);
    }

    public void trace(Marker marker, String msg) {
        this.log.trace(marker, msg);
    }

    public void trace(Marker marker, String format, Object arg) {
        this.log.trace(marker, format, arg);
    }

    public void trace(Marker marker, String format, Object arg1, Object arg2) {
        this.log.trace(marker, format, arg1, arg2);
    }

    public void trace(Marker marker, String format, Object... argArray) {
        this.log.trace(marker, format, argArray);
    }

    public void trace(Marker marker, String msg, Throwable t) {
        this.log.trace(marker, msg, t);
    }

    public boolean isDebugEnabled() {
        return this.log.isDebugEnabled();
    }

    public void debug(String msg) {
        this.log.debug(msg);
    }

    public void debug(String format, Object arg) {
        this.log.debug(format, arg);
    }

    public void debug(String format, Object arg1, Object arg2) {
        this.log.debug(format, arg1, arg2);
    }

    public void debug(String format, Object... arguments) {
        this.log.debug(format, arguments);
    }

    public void debug(String msg, Throwable t) {
        this.log.debug(msg, t);
    }

    public boolean isDebugEnabled(Marker marker) {
        return this.log.isDebugEnabled(marker);
    }

    public void debug(Marker marker, String msg) {
        this.log.debug(marker, msg);
    }

    public void debug(Marker marker, String format, Object arg) {
        this.log.debug(marker, format, arg);
    }

    public void debug(Marker marker, String format, Object arg1, Object arg2) {
        this.log.debug(marker, format, arg1, arg2);
    }

    public void debug(Marker marker, String format, Object... arguments) {
        this.log.debug(marker, format, arguments);
    }

    public void debug(Marker marker, String msg, Throwable t) {
        this.log.debug(marker, msg, t);
    }

    public boolean isInfoEnabled() {
        return this.log.isInfoEnabled();
    }

    public void info(String msg) {
        this.log.info(msg);
    }

    public void info(String format, Object arg) {
        this.log.info(format, arg);
    }

    public void info(String format, Object arg1, Object arg2) {
        this.log.info(format, arg1, arg2);
    }

    public void info(String format, Object... arguments) {
        this.log.info(format, arguments);
    }

    public void info(String msg, Throwable t) {
        this.log.info(msg, t);
    }

    public boolean isInfoEnabled(Marker marker) {
        return this.log.isInfoEnabled(marker);
    }

    public void info(Marker marker, String msg) {
        this.log.info(marker, msg);
    }

    public void info(Marker marker, String format, Object arg) {
        this.log.info(marker, format, arg);
    }

    public void info(Marker marker, String format, Object arg1, Object arg2) {
        this.log.info(marker, format, arg1, arg2);
    }

    public void info(Marker marker, String format, Object... arguments) {
        this.log.info(marker, format, arguments);
    }

    public void info(Marker marker, String msg, Throwable t) {
        this.log.info(marker, msg, t);
    }

    public boolean isWarnEnabled() {
        return this.log.isWarnEnabled();
    }

    public void warn(String msg) {
        this.log.warn(msg);
    }

    public void warn(String format, Object arg) {
        this.log.warn(format, arg);
    }

    public void warn(String format, Object... arguments) {
        this.log.warn(format, arguments);
    }

    public void warn(String format, Object arg1, Object arg2) {
        this.log.warn(format, arg1, arg2);
    }

    public void warn(String msg, Throwable t) {
        this.log.warn(msg, t);
    }

    public boolean isWarnEnabled(Marker marker) {
        return this.log.isWarnEnabled(marker);
    }

    public void warn(Marker marker, String msg) {
        this.log.warn(marker, msg);
    }

    public void warn(Marker marker, String format, Object arg) {
        this.log.warn(marker, format, arg);
    }

    public void warn(Marker marker, String format, Object arg1, Object arg2) {
        this.log.warn(marker, format, arg1, arg2);
    }

    public void warn(Marker marker, String format, Object... arguments) {
        this.log.warn(marker, format, arguments);
    }

    public void warn(Marker marker, String msg, Throwable t) {
        this.log.warn(marker, msg, t);
    }

    public boolean isErrorEnabled() {
        return this.log.isErrorEnabled();
    }

    public void error(String msg) {
        this.log.error(msg);
    }

    public void error(@Nullable ErrorCode errorCode, String msg) {
        this.log.error(appendErrorCode(msg, errorCode));
    }

    public void error(String format, Object arg) {
        this.log.error(format, arg);
    }

    public void error(@Nullable ErrorCode errorCode, String format, Object arg) {
        this.log.error(appendErrorCode(format, errorCode), arg);
    }

    public void error(String format, Object arg1, Object arg2) {
        this.log.error(format, arg1, arg2);
    }

    public void error(@Nullable ErrorCode errorCode, String format, Object arg1, Object arg2) {
        this.log.error(appendErrorCode(format, errorCode), arg1, arg2);
    }

    public void error(String format, Object... arguments) {
        this.log.error(format, arguments);
    }

    public void error(@Nullable ErrorCode errorCode, String format, Object... arguments) {
        this.log.error(appendErrorCode(format, errorCode), arguments);
    }

    public void error(String msg, Throwable t) {
        this.log.error(msg, t);
    }

    public void error(@Nullable ErrorCode errorCode, String msg, Throwable t) {
        this.log.error(appendErrorCode(msg, errorCode), t);
    }

    public boolean isErrorEnabled(Marker marker) {
        return this.log.isErrorEnabled(marker);
    }

    public void error(Marker marker, String msg) {
        this.log.error(marker, msg);
    }

    public void error(Marker marker, String format, Object arg) {
        this.log.error(marker, format, arg);
    }

    public void error(Marker marker, String format, Object arg1, Object arg2) {
        this.log.error(marker, format, arg1, arg2);
    }

    public void error(Marker marker, String format, Object... arguments) {
        this.log.error(marker, format, arguments);
    }

    public void error(Marker marker, String msg, Throwable t) {
        this.log.error(marker, msg, t);
    }

    private String appendErrorCode(String msg, @Nullable ErrorCode errorCode) {
        if (errorCode == null) {
            return msg;
        }
        return String.format(ERROR_CODE_LOG_PREFIX_TEMPLATE, errorCode.getFormattedCode()) + msg;
    }
}
