/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.plugin.logger;

import java.lang.reflect.Method;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.Logger;

public class LoggingEventInvoker {

    private static final Logger logger = Agent.getLogger(LoggingEventInvoker.class);

    private final @Nullable Method getLoggerNameMethod;

    private final @Nullable Method getFormattedMessageMethod;
    private final @Nullable Method getLevelMethod;

    private final @Nullable Method getThrowableProxyMethod;
    private final @Nullable Method getThrowableMethod;

    private final @Nullable Method toIntMethod;

    public LoggingEventInvoker(Class<?> clazz) {
        Class<?> loggerClass = getLoggerClass(clazz);
        getLoggerNameMethod = Invokers.getMethod(loggerClass, "getName");
        Class<?> loggingEventClass = getLoggingEventClass(clazz);
        getFormattedMessageMethod = Invokers.getMethod(loggingEventClass, "getFormattedMessage");
        getLevelMethod = Invokers.getMethod(loggingEventClass, "getLevel");
        if (loggingEventClass == null) {
            getThrowableProxyMethod = null;
            getThrowableMethod = null;
        } else {
            Method localGetThrowableProxyMethod = null;
            Method localGetThrowableMethod = null;
            try {
                localGetThrowableProxyMethod = loggingEventClass.getMethod("getThrowableProxy");
                Class<?> throwableProxyClass =
                        Class.forName("ch.qos.logback.classic.spi.ThrowableProxy", false,
                                clazz.getClassLoader());
                localGetThrowableMethod = throwableProxyClass.getMethod("getThrowable");
            } catch (Throwable t) {
                logger.debug(t.getMessage(), t);
                try {
                    localGetThrowableProxyMethod =
                            loggingEventClass.getMethod("getThrowableInformation");
                    Class<?> throwableInformationClass =
                            Class.forName("ch.qos.logback.classic.spi.ThrowableInformation", false,
                                    clazz.getClassLoader());
                    localGetThrowableMethod =
                            Invokers.getMethod(throwableInformationClass, "getThrowable");
                } catch (Throwable tt) {
                    // log at debug
                    logger.debug(tt.getMessage(), tt);
                    // log original at warn
                    logger.warn(t.getMessage(), t);
                }
            }
            getThrowableProxyMethod = localGetThrowableProxyMethod;
            getThrowableMethod = localGetThrowableMethod;
        }
        toIntMethod = Invokers.getMethod(getLevelClass(clazz), "toInt");
    }

    public String getFormattedMessage(Object loggingEvent) {
        return Invokers.invoke(getFormattedMessageMethod, loggingEvent, "");
    }

    public int getLevel(Object loggingEvent) {
        Object level = Invokers.invoke(getLevelMethod, loggingEvent, null);
        if (level == null) {
            return 0;
        }
        return Invokers.invoke(toIntMethod, level, 0);
    }

    public @Nullable Throwable getThrowable(Object loggingEvent) {
        Object throwableInformation =
                Invokers.invoke(getThrowableProxyMethod, loggingEvent, null);
        if (throwableInformation == null) {
            return null;
        }
        return Invokers.</*@Nullable*/ Throwable>invoke(getThrowableMethod, throwableInformation,
                null);
    }

    public String getLoggerName(Object logger) {
        return Invokers.invoke(getLoggerNameMethod, logger, "");
    }

    private static @Nullable Class<?> getLoggerClass(Class<?> clazz) {
        try {
            return Class.forName("ch.qos.logback.classic.Logger", false, clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }

    private static @Nullable Class<?> getLoggingEventClass(Class<?> clazz) {
        try {
            return Class.forName("ch.qos.logback.classic.spi.LoggingEvent", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }

    private static @Nullable Class<?> getLevelClass(Class<?> clazz) {
        try {
            return Class.forName("ch.qos.logback.classic.Level", false,
                    clazz.getClassLoader());
        } catch (ClassNotFoundException e) {
            logger.warn(e.getMessage(), e);
        }
        return null;
    }
}
