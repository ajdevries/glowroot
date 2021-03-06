/*
 * Copyright 2015-2016 the original author or authors.
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
package org.glowroot.storage.repo.helper;

import java.util.List;
import java.util.regex.Pattern;

import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.glowroot.storage.repo.GaugeValueRepository.Gauge;
import org.glowroot.storage.repo.ImmutableGauge;

public class Gauges {

    private static final ImmutableList<UnitPattern> unitPatterns;

    static {
        List<UnitPattern> patterns = Lists.newArrayList();
        patterns.add(new UnitPattern(
                "java.lang:type=Memory:(Non)?HeapMemoryUsage\\/(init|used|committed|max)",
                "bytes"));
        patterns.add(new UnitPattern(
                "java.lang:type=OperatingSystem:(Free|Total)(Physical|Swap)MemorySize", "bytes"));
        patterns.add(new UnitPattern("java.lang:type=Runtime:Uptime", "milliseconds"));
        patterns.add(new UnitPattern("java.lang:type=Threading:CurrentThread(Cpu|User)Time",
                "nanoseconds"));
        patterns.add(new UnitPattern("java.lang:type=MemoryPool,name=[a-zA-Z0-9 ]+:(Peak)?Usage"
                + "\\/(init|used|committed|max)", "bytes"));
        patterns.add(new UnitPattern(
                "java.lang:type=GarbageCollector,name=[a-zA-Z0-9 ]+:LastGcInfo\\/duration",
                "milliseconds"));
        patterns.add(
                new UnitPattern("java.lang:type=GarbageCollector,name=[a-zA-Z0-9 ]+:CollectionTime",
                        "milliseconds"));
        patterns.add(
                new UnitPattern("java.lang:type=Compilation:TotalCompilationTime", "milliseconds"));
        unitPatterns = ImmutableList.copyOf(patterns);
    }

    private Gauges() {}

    public static Gauge getGauge(String gaugeName) {
        int index = gaugeName.lastIndexOf(':');
        String mbeanObjectName = gaugeName.substring(0, index);
        String mbeanAttributeName = gaugeName.substring(index + 1);
        boolean counter = mbeanAttributeName.endsWith("[counter]");
        if (counter) {
            mbeanAttributeName = mbeanAttributeName.substring(0,
                    mbeanAttributeName.length() - "[counter]".length());
        }
        String display = display(mbeanObjectName) + '/' + mbeanAttributeName;
        return ImmutableGauge.of(gaugeName, display, counter, unit(gaugeName));
    }

    public static String display(String mbeanObjectName) {
        // e.g. java.lang:name=PS Eden Space,type=MemoryPool
        List<String> parts = Splitter.on(CharMatcher.anyOf(":,")).splitToList(mbeanObjectName);
        StringBuilder name = new StringBuilder();
        name.append(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            name.append('/');
            name.append(parts.get(i).split("=")[1]);
        }
        return name.toString();
    }

    private static String unit(String gaugeName) {
        if (gaugeName.endsWith("[counter]")) {
            return getBaseUnit(gaugeName.substring(0, gaugeName.length() - "[counter]".length()))
                    + " per second";
        } else {
            return getBaseUnit(gaugeName);
        }
    }

    private static String getBaseUnit(String gaugeName) {
        for (UnitPattern unitPattern : unitPatterns) {
            if (unitPattern.pattern.matcher(gaugeName).matches()) {
                return unitPattern.unit;
            }
        }
        return "";
    }

    private static class UnitPattern {

        private final Pattern pattern;
        private final String unit;

        private UnitPattern(String pattern, String unit) {
            this.pattern = Pattern.compile(pattern);
            this.unit = unit;
        }
    }
}
