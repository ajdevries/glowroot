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
package org.glowroot.trace.model;

import java.util.List;

import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.api.ErrorMessage;
import org.glowroot.api.MessageSupplier;
import org.glowroot.common.Ticker;
import org.glowroot.markers.GuardedBy;

/**
 * The "span" terminology is borrowed from <a
 * href="http://research.google.com/pubs/pub36356.html">Dapper</a>.
 * 
 * This must support updating by a single thread and reading by multiple threads.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
class RootSpan {

    private static final Logger logger = LoggerFactory.getLogger(RootSpan.class);

    // spanStack doesn't need to be thread safe since it is only accessed by the trace thread
    private final List<Span> spanStack = Lists.newArrayList();

    private final long startTick;
    // not volatile, so depends on memory barrier in Trace for visibility
    @MonotonicNonNull
    private Long endTick;

    private final Span rootSpan;
    // very little contention on spans, so synchronized ArrayList performs better than
    // ConcurrentLinkedQueue
    @GuardedBy("spans")
    private final List<Span> spans = Lists.newArrayList();

    // this doesn't need to be volatile since it is only accessed by the trace thread
    private boolean spanLimitExceeded;

    private final Ticker ticker;

    RootSpan(MessageSupplier messageSupplier, MetricTimerExt metricTimer, long startTick,
            Ticker ticker) {
        this.startTick = startTick;
        this.ticker = ticker;
        rootSpan = new Span(messageSupplier, startTick, 0, metricTimer);
        spanStack.add(rootSpan);
        synchronized (spans) {
            spans.add(rootSpan);
        }
    }

    Span getRootSpan() {
        return rootSpan;
    }

    ImmutableList<Span> getSpansCopy() {
        synchronized (spans) {
            return ImmutableList.copyOf(spans);
        }
    }

    int getSize() {
        synchronized (spans) {
            return spans.size();
        }
    }

    long getStartTick() {
        return startTick;
    }

    @Nullable
    Long getEndTick() {
        return endTick;
    }

    // duration of trace in nanoseconds
    long getDuration() {
        return endTick == null ? ticker.read() - startTick : endTick - startTick;
    }

    boolean isCompleted() {
        return endTick != null;
    }

    Span pushSpan(long startTick, MessageSupplier messageSupplier, MetricTimerExt metricTimer) {
        Span span = createSpan(startTick, messageSupplier, null, metricTimer, false);
        spanStack.add(span);
        synchronized (spans) {
            spans.add(span);
        }
        return span;
    }

    // typically pop() methods don't require the objects to pop, but for safety, the span is
    // passed in just to make sure it is the one on top (and if not, then pop until it is found,
    // preventing any nasty bugs from a missed pop, e.g. a span never being marked as complete)
    void popSpan(Span span, long endTick, @Nullable ErrorMessage errorMessage) {
        span.setErrorMessage(errorMessage);
        span.setEndTick(endTick);
        popSpanSafe(span);
        if (spanStack.isEmpty()) {
            this.endTick = endTick;
        }
    }

    Span addSpan(long startTick, long endTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, boolean limitBypassed) {
        Span span = createSpan(startTick, messageSupplier, errorMessage, null, limitBypassed);
        synchronized (spans) {
            spans.add(span);
        }
        span.setEndTick(endTick);
        return span;
    }

    void addSpanLimitExceededMarkerIfNeeded() {
        if (spanLimitExceeded) {
            return;
        }
        spanLimitExceeded = true;
        synchronized (spans) {
            spans.add(Span.getLimitExceededMarker());
        }
    }

    private Span createSpan(long startTick, @Nullable MessageSupplier messageSupplier,
            @Nullable ErrorMessage errorMessage, @Nullable MetricTimerExt metricTimer,
            boolean limitBypassed) {
        if (spanLimitExceeded && !limitBypassed) {
            // just in case the spanLimit property is changed in the middle of a trace this resets
            // the flag so that it can be triggered again (and possibly then a second limit marker)
            spanLimitExceeded = false;
            // also a different marker ("limit extended") is placed in the spans so that the ui can
            // display this scenario sensibly
            synchronized (spans) {
                spans.add(Span.getLimitExtendedMarker());
            }
        }
        Span currentSpan = spanStack.get(spanStack.size() - 1);
        int nestingLevel;
        if (spanLimitExceeded && limitBypassed) {
            // limit bypassed spans have no proper nesting, so put them directly under the root
            nestingLevel = 1;
        } else {
            nestingLevel = currentSpan.getNestingLevel() + 1;
        }
        Span span = new Span(messageSupplier, startTick, nestingLevel, metricTimer);
        span.setErrorMessage(errorMessage);
        return span;
    }

    private void popSpanSafe(Span span) {
        if (spanStack.isEmpty()) {
            logger.error("span stack is empty, cannot pop span: {}", span);
            return;
        }
        Span pop = spanStack.remove(spanStack.size() - 1);
        if (!pop.equals(span)) {
            // somehow(?) a pop was missed (or maybe too many pops), this is just damage control
            logger.error("found span {} at top of stack when expecting span {}", pop, span);
            while (!spanStack.isEmpty() && !pop.equals(span)) {
                pop = spanStack.remove(spanStack.size() - 1);
            }
            if (spanStack.isEmpty() && !pop.equals(span)) {
                logger.error("popped entire stack, never found span: {}", span);
            }
        }
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("spanStack", spanStack)
                .add("startTick", startTick)
                .add("endTick", endTick)
                .add("rootSpan", rootSpan)
                .add("spans", getSpansCopy())
                .toString();
    }
}
