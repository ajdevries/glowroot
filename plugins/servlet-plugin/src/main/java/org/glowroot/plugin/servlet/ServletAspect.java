/*
 * Copyright 2011-2014 the original author or authors.
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
package org.glowroot.plugin.servlet;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import org.glowroot.api.CompletedTraceEntry;
import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MetricName;
import org.glowroot.api.PluginServices;
import org.glowroot.api.TraceEntry;
import org.glowroot.api.weaving.BindClassMeta;
import org.glowroot.api.weaving.BindParameter;
import org.glowroot.api.weaving.BindThrowable;
import org.glowroot.api.weaving.BindTraveler;
import org.glowroot.api.weaving.IsEnabled;
import org.glowroot.api.weaving.OnAfter;
import org.glowroot.api.weaving.OnBefore;
import org.glowroot.api.weaving.OnReturn;
import org.glowroot.api.weaving.OnThrow;
import org.glowroot.api.weaving.Pointcut;

// only the calls to the top-most Filter and to the top-most Servlet are captured
//
// this plugin is careful not to rely on request or session objects being thread-safe
//
// TODO add support for async servlets (servlet 3.0)
public class ServletAspect {

    private static final PluginServices pluginServices = PluginServices.get("servlet");

    private static final ThreadLocal</*@Nullable*/ServletMessageSupplier> topLevel =
            new ThreadLocal</*@Nullable*/ServletMessageSupplier>();

    // the life of this thread local is tied to the life of the topLevel thread local
    // it is only created if the topLevel thread local exists, and it is cleared when topLevel
    // thread local is cleared
    private static final ThreadLocal</*@Nullable*/ErrorMessage> sendError =
            new ThreadLocal</*@Nullable*/ErrorMessage>();

    @Pointcut(className = "javax.servlet.Servlet", methodName = "service",
            methodParameterTypes = {"javax.servlet.ServletRequest",
                    "javax.servlet.ServletResponse"}, metricName = "http request")
    public static class ServiceAdvice {
        private static final MetricName metricName =
                pluginServices.getMetricName(ServiceAdvice.class);
        @IsEnabled
        public static boolean isEnabled() {
            // only enabled if it is not contained in another servlet or filter
            return topLevel.get() == null && pluginServices.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter Object request,
                @BindClassMeta RequestInvoker requestInvoker,
                @BindClassMeta SessionInvoker sessionInvoker) {
            // request parameter map is collected in GetParameterAdvice
            // session info is collected here if the request already has a session
            ServletMessageSupplier messageSupplier;
            Object session = requestInvoker.getSession(request);
            String requestUri = requestInvoker.getRequestURI(request);
            // don't convert null to empty, since null means no query string, while empty means
            // url ended with ? but nothing after that
            String requestQueryString = requestInvoker.getQueryString(request);
            String requestMethod = requestInvoker.getMethod(request);
            ImmutableMap<String, Object> requestHeaders =
                    DetailCapture.captureRequestHeaders(request, requestInvoker);
            if (session == null) {
                messageSupplier = new ServletMessageSupplier(requestMethod, requestUri,
                        requestQueryString, requestHeaders, null, null);
            } else {
                ImmutableMap<String, String> sessionAttributes =
                        HttpSessions.getSessionAttributes(session, sessionInvoker);
                messageSupplier = new ServletMessageSupplier(requestMethod, requestUri,
                        requestQueryString, requestHeaders, sessionInvoker.getId(session),
                        sessionAttributes);
            }
            topLevel.set(messageSupplier);
            TraceEntry traceEntry = pluginServices.startTransaction("Servlet", requestUri,
                    messageSupplier, metricName);
            String userPrincipalName = requestInvoker.getUserPrincipalName(request);
            if (userPrincipalName != null) {
                pluginServices.setTransactionUser(userPrincipalName);
            }
            // Glowroot-Transaction-Name header is useful for automated tests which want to send a
            // more specific name for the transaction
            String transactionNameOverride =
                    requestInvoker.getHeader(request, "Glowroot-Transaction-Name");
            if (transactionNameOverride != null) {
                pluginServices.setTransactionName(transactionNameOverride);
            }
            if (session != null) {
                String sessionUserAttributePath =
                        ServletPluginProperties.sessionUserAttributePath();
                if (!sessionUserAttributePath.isEmpty()) {
                    // capture user now, don't use a lazy supplier
                    String user = HttpSessions.getSessionAttributeTextValue(session,
                            sessionUserAttributePath, sessionInvoker);
                    pluginServices.setTransactionUser(user);
                }
            }
            return traceEntry;
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            ErrorMessage errorMessage = sendError.get();
            if (errorMessage != null) {
                traceEntry.endWithError(errorMessage);
                sendError.remove();
            } else {
                traceEntry.end();
            }
            topLevel.remove();
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            // ignoring potential sendError since this seems worse
            sendError.remove();
            traceEntry.endWithError(ErrorMessage.from(t));
            topLevel.remove();
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServlet", methodName = "do*",
            methodParameterTypes = {"javax.servlet.http.HttpServletRequest",
                    "javax.servlet.http.HttpServletResponse"}, metricName = "http request")
    public static class DoMethodsAdvice extends ServiceAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServiceAdvice.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter Object request,
                @BindClassMeta RequestInvoker requestInvoker,
                @BindClassMeta SessionInvoker sessionInvoker) {
            return ServiceAdvice.onBefore(request, requestInvoker, sessionInvoker);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            ServiceAdvice.onReturn(traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, traceEntry);
        }
    }

    @Pointcut(className = "javax.servlet.Filter", methodName = "doFilter", methodParameterTypes = {
            "javax.servlet.ServletRequest", "javax.servlet.ServletResponse",
            "javax.servlet.FilterChain"}, metricName = "http request")
    public static class DoFilterAdvice extends ServiceAdvice {
        @IsEnabled
        public static boolean isEnabled() {
            return ServiceAdvice.isEnabled();
        }
        @OnBefore
        public static TraceEntry onBefore(@BindParameter Object request,
                @BindClassMeta RequestInvoker requestInvoker,
                @BindClassMeta SessionInvoker sessionInvoker) {
            return ServiceAdvice.onBefore(request, requestInvoker, sessionInvoker);
        }
        @OnReturn
        public static void onReturn(@BindTraveler TraceEntry traceEntry) {
            ServiceAdvice.onReturn(traceEntry);
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler TraceEntry traceEntry) {
            ServiceAdvice.onThrow(t, traceEntry);
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "sendError",
            methodParameterTypes = {"int", ".."}, ignoreSelfNested = true)
    public static class SendErrorAdvice {
        @OnAfter
        public static void onAfter(@BindParameter Integer statusCode) {
            // only capture 5xx server errors
            if (statusCode >= 500 && topLevel.get() != null) {
                ErrorMessage errorMessage = ErrorMessage.from("sendError, HTTP status code "
                        + statusCode);
                CompletedTraceEntry traceEntry = pluginServices.addTraceEntry(errorMessage);
                traceEntry.captureStackTrace();
                sendError.set(errorMessage);
            }
        }
    }

    @Pointcut(className = "javax.servlet.http.HttpServletResponse", methodName = "setStatus",
            methodParameterTypes = {"int", ".."}, ignoreSelfNested = true)
    public static class SetStatusAdvice {
        @OnAfter
        public static void onAfter(@BindParameter Integer statusCode) {
            // only capture 5xx server errors
            if (statusCode >= 500 && topLevel.get() != null) {
                ErrorMessage errorMessage = ErrorMessage.from("setStatus, HTTP status code "
                        + statusCode);
                CompletedTraceEntry traceEntry = pluginServices.addTraceEntry(errorMessage);
                traceEntry.captureStackTrace();
                sendError.set(errorMessage);
            }
        }
    }

    static @Nullable ServletMessageSupplier getServletMessageSupplier() {
        return topLevel.get();
    }
}
