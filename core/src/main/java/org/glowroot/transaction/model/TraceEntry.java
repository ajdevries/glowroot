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
package org.glowroot.transaction.model;

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.api.internal.ReadableErrorMessage;

// this supports updating by a single thread and reading by multiple threads
public class TraceEntry {

    private static final TraceEntry limitExceededMarker = new TraceEntry(null, 0, 0, null);

    private static final TraceEntry limitExtendedMarker = new TraceEntry(null, 0, 0, null);

    private final @Nullable MessageSupplier messageSupplier;
    // not volatile, so depends on memory barrier in Trace for visibility
    private @Nullable ErrorMessage errorMessage;

    private final long startTick;
    // not volatile, so depends on memory barrier in Trace for visibility
    private boolean completed;
    // not volatile, so depends on memory barrier in Trace for visibility
    private long endTick;

    private final int nestingLevel;

    // the associated metric, stored here so it can be accessed in org.glowroot.api.TraceEntry.end()
    private final @Nullable TransactionMetricExt transactionMetric;
    // not volatile, so depends on memory barrier in Trace for visibility
    private @Nullable ImmutableList<StackTraceElement> stackTrace;

    TraceEntry(@Nullable MessageSupplier messageSupplier, long startTick, int nesting,
            @Nullable TransactionMetricExt transactionMetric) {
        this.messageSupplier = messageSupplier;
        this.startTick = startTick;
        this.nestingLevel = nesting;
        this.transactionMetric = transactionMetric;
    }

    public @Nullable MessageSupplier getMessageSupplier() {
        return messageSupplier;
    }

    public @Nullable ReadableErrorMessage getErrorMessage() {
        return (ReadableErrorMessage) errorMessage;
    }

    public long getStartTick() {
        return startTick;
    }

    public boolean isCompleted() {
        return completed;
    }

    public long getEndTick() {
        return endTick;
    }

    public int getNestingLevel() {
        return nestingLevel;
    }

    public @Nullable ImmutableList<StackTraceElement> getStackTrace() {
        return stackTrace;
    }

    public boolean isLimitExceededMarker() {
        return this == limitExceededMarker;
    }

    public boolean isLimitExtendedMarker() {
        return this == limitExtendedMarker;
    }

    @Nullable
    TransactionMetricExt getTransactionMetric() {
        return transactionMetric;
    }

    void setErrorMessage(@Nullable ErrorMessage errorMessage) {
        this.errorMessage = errorMessage;
    }

    void setEndTick(long endTick) {
        this.endTick = endTick;
        this.completed = true;
    }

    public void setStackTrace(ImmutableList<StackTraceElement> stackTrace) {
        this.stackTrace = stackTrace;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("message", messageSupplier == null ? null : messageSupplier.get())
                .add("errorMessage", errorMessage)
                .add("startTick", startTick)
                .add("completed", completed)
                .add("endTick", endTick)
                .add("nestingLevel", nestingLevel)
                .add("transactionMetric", transactionMetric)
                .add("stackTrace", stackTrace)
                .add("limitExceededMarker", isLimitExceededMarker())
                .add("limitExtendedMarker", isLimitExtendedMarker())
                .toString();
    }

    static TraceEntry getLimitExceededMarker() {
        return limitExceededMarker;
    }

    static TraceEntry getLimitExtendedMarker() {
        return limitExtendedMarker;
    }
}
