/**
 * Copyright 2012 the original author or authors.
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
package org.informantproject.testing.ui;

import java.util.concurrent.atomic.AtomicInteger;

import org.informantproject.api.PluginServices;
import org.informantproject.api.SpanContextMap;
import org.informantproject.api.SpanDetail;
import org.informantproject.shaded.aspectj.lang.ProceedingJoinPoint;
import org.informantproject.shaded.aspectj.lang.annotation.Around;
import org.informantproject.shaded.aspectj.lang.annotation.Aspect;
import org.informantproject.shaded.aspectj.lang.annotation.Pointcut;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@Aspect
public class ExpensiveCallAspect {

    private static final PluginServices pluginServices = PluginServices
            .get("org.informantproject:informant-ui-testing");

    private static final AtomicInteger counter = new AtomicInteger();

    @Pointcut("if()")
    public static boolean isPluginEnabled() {
        return pluginServices.isEnabled();
    }

    @Pointcut("call(void org.informantproject.testing.ui.ExpensiveCall.execute())")
    void expensivePointcut() {}

    @Around("isPluginEnabled() && expensivePointcut() && target(expensive)")
    public Object nestableAdvice(ProceedingJoinPoint joinPoint, ExpensiveCall expensive)
            throws Throwable {

        return pluginServices.executeSpan(getSpanDetail(expensive), joinPoint, "expensive");
    }

    private SpanDetail getSpanDetail(final ExpensiveCall expensive) {
        return new SpanDetail() {
            public String getDescription() {
                return expensive.getDescription();
            }
            public SpanContextMap getContextMap() {
                if (counter.getAndIncrement() % 10 == 0) {
                    return SpanContextMap.of("attr1", "value1", "attr2", "value2", "attr3",
                            SpanContextMap.of("attr31", SpanContextMap.of("attr311", "value311",
                                    "attr312", "value312"), "attr32", "value32", "attr33",
                                    "value33"));
                } else {
                    return null;
                }
            }
        };
    }
}
