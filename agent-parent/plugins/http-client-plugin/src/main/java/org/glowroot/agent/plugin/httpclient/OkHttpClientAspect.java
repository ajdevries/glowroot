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
package org.glowroot.agent.plugin.httpclient;

import java.net.URL;

import javax.annotation.Nullable;

import org.glowroot.agent.plugin.api.Agent;
import org.glowroot.agent.plugin.api.AsyncTraceEntry;
import org.glowroot.agent.plugin.api.AuxThreadContext;
import org.glowroot.agent.plugin.api.MessageSupplier;
import org.glowroot.agent.plugin.api.ThreadContext;
import org.glowroot.agent.plugin.api.TimerName;
import org.glowroot.agent.plugin.api.TraceEntry;
import org.glowroot.agent.plugin.api.weaving.BindClassMeta;
import org.glowroot.agent.plugin.api.weaving.BindParameter;
import org.glowroot.agent.plugin.api.weaving.BindReceiver;
import org.glowroot.agent.plugin.api.weaving.BindThrowable;
import org.glowroot.agent.plugin.api.weaving.BindTraveler;
import org.glowroot.agent.plugin.api.weaving.Mixin;
import org.glowroot.agent.plugin.api.weaving.OnBefore;
import org.glowroot.agent.plugin.api.weaving.OnReturn;
import org.glowroot.agent.plugin.api.weaving.OnThrow;
import org.glowroot.agent.plugin.api.weaving.Pointcut;
import org.glowroot.agent.plugin.api.weaving.Shim;

public class OkHttpClientAspect {

    @Shim("com.squareup.okhttp.Request")
    public interface Request {
        @Nullable
        String method();
        @Nullable
        URL url();
    }

    // the field and method names are verbose to avoid conflict since they will become fields
    // and methods in all classes that extend com.squareup.okhttp.Callback
    @Mixin("com.squareup.okhttp.Callback")
    public abstract static class CallbackImpl implements CallbackMixin {

        private volatile @Nullable AsyncTraceEntry glowroot$asyncTraceEntry;
        private volatile @Nullable AuxThreadContext glowroot$auxContext;

        @Override
        public @Nullable AsyncTraceEntry glowroot$getAsyncTraceEntry() {
            return glowroot$asyncTraceEntry;
        }

        @Override
        public void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry) {
            this.glowroot$asyncTraceEntry = asyncTraceEntry;
        }

        @Override
        public @Nullable AuxThreadContext glowroot$getAuxContext() {
            return glowroot$auxContext;
        }

        @Override
        public void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext) {
            this.glowroot$auxContext = auxContext;
        }
    }

    // the method names are verbose to avoid conflict since they will become methods in all classes
    // that extend com.squareup.okhttp.Callback
    public interface CallbackMixin {

        @Nullable
        AsyncTraceEntry glowroot$getAsyncTraceEntry();

        void glowroot$setAsyncTraceEntry(@Nullable AsyncTraceEntry asyncTraceEntry);

        @Nullable
        AuxThreadContext glowroot$getAuxContext();

        void glowroot$setAuxContext(@Nullable AuxThreadContext auxContext);
    }

    @Pointcut(className = "com.squareup.okhttp.Call", methodName = "execute",
            methodParameterTypes = {}, nestingGroup = "http-client",
            timerName = "http client request")
    public static class ExecuteAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);
        @OnBefore
        public static @Nullable TraceEntry onBefore(ThreadContext context,
                @BindReceiver Object call, @BindClassMeta OkHttpClientCallInvoker callInvoker) {
            Request originalRequest = (Request) callInvoker.getOriginalRequest(call);
            if (originalRequest == null) {
                return null;
            }
            String method = originalRequest.method();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            URL urlObj = originalRequest.url();
            String url;
            if (urlObj == null) {
                url = "";
            } else {
                url = urlObj.toString();
            }
            return context.startServiceCallEntry("HTTP", method + Uris.stripQueryString(url),
                    MessageSupplier.from("http client request: {}{}", method, url), timerName);
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(throwable);
            }
        }
    }

    @Pointcut(className = "com.squareup.okhttp.Call", methodName = "enqueue",
            methodParameterTypes = {"com.squareup.okhttp.Callback"}, nestingGroup = "http-client",
            timerName = "http client request")
    public static class EnqueueAdvice {
        private static final TimerName timerName = Agent.getTimerName(ExecuteAdvice.class);
        @OnBefore
        public static @Nullable AsyncTraceEntry onBefore(ThreadContext context,
                @BindReceiver Object call, @BindClassMeta OkHttpClientCallInvoker callInvoker) {
            Request originalRequest = (Request) callInvoker.getOriginalRequest(call);
            if (originalRequest == null) {
                return null;
            }
            String method = originalRequest.method();
            if (method == null) {
                method = "";
            } else {
                method += " ";
            }
            URL urlObj = originalRequest.url();
            String url;
            if (urlObj == null) {
                url = "";
            } else {
                url = urlObj.toString();
            }
            return context.startAsyncServiceCallEntry("HTTP", method + Uris.stripQueryString(url),
                    MessageSupplier.from("http client request: {}{}", method, url), timerName);
        }
        @OnReturn
        public static void onReturn(ThreadContext context,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry,
                @BindParameter @Nullable CallbackMixin callback) {
            if (asyncTraceEntry == null) {
                return;
            }
            asyncTraceEntry.stopSyncTimer();
            if (callback == null) {
                asyncTraceEntry.end();
                return;
            }
            callback.glowroot$setAsyncTraceEntry(asyncTraceEntry);
            callback.glowroot$setAuxContext(context.createAuxThreadContext());
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable throwable,
                @BindTraveler @Nullable AsyncTraceEntry asyncTraceEntry) {
            if (asyncTraceEntry != null) {
                asyncTraceEntry.stopSyncTimer();
                asyncTraceEntry.endWithError(throwable);
            }
        }
    }

    @Pointcut(className = "com.squareup.okhttp.Callback", methodName = "onResponse|onFailure",
            methodParameterTypes = {".."})
    public static class CallbackAdvice {
        @OnBefore
        public static @Nullable TraceEntry onBefore(@BindReceiver CallbackMixin callback) {
            AsyncTraceEntry asyncTraceEntry = callback.glowroot$getAsyncTraceEntry();
            if (asyncTraceEntry != null) {
                asyncTraceEntry.end();
                callback.glowroot$setAsyncTraceEntry(null);
            }
            AuxThreadContext auxContext = callback.glowroot$getAuxContext();
            if (auxContext != null) {
                callback.glowroot$setAuxContext(null);
                return auxContext.start();
            }
            return null;
        }
        @OnReturn
        public static void onReturn(@BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.end();
            }
        }
        @OnThrow
        public static void onThrow(@BindThrowable Throwable t,
                @BindTraveler @Nullable TraceEntry traceEntry) {
            if (traceEntry != null) {
                traceEntry.endWithError(t);
            }
        }
    }
}
