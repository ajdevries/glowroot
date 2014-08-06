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
package org.glowroot.container.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.google.common.base.Objects;
import org.checkerframework.checker.nullness.qual.Nullable;

import static org.glowroot.container.common.ObjectMappers.checkRequiredProperty;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class GeneralConfig {

    private boolean enabled;
    private int storeThresholdMillis;
    private int stuckThresholdSeconds;
    private int maxSpans;
    private boolean threadInfoEnabled;
    private boolean gcInfoEnabled;

    private final String version;

    public GeneralConfig(String version) {
        this.version = version;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getStoreThresholdMillis() {
        return storeThresholdMillis;
    }

    public void setStoreThresholdMillis(int storeThresholdMillis) {
        this.storeThresholdMillis = storeThresholdMillis;
    }

    public int getStuckThresholdSeconds() {
        return stuckThresholdSeconds;
    }

    public void setStuckThresholdSeconds(int stuckThresholdSeconds) {
        this.stuckThresholdSeconds = stuckThresholdSeconds;
    }

    public int getMaxSpans() {
        return maxSpans;
    }

    public void setMaxSpans(int maxSpans) {
        this.maxSpans = maxSpans;
    }

    public boolean isThreadInfoEnabled() {
        return threadInfoEnabled;
    }

    public void setThreadInfoEnabled(boolean threadInfoEnabled) {
        this.threadInfoEnabled = threadInfoEnabled;
    }

    public boolean isGcInfoEnabled() {
        return gcInfoEnabled;
    }

    public void setGcInfoEnabled(boolean gcInfoEnabled) {
        this.gcInfoEnabled = gcInfoEnabled;
    }

    public String getVersion() {
        return version;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (obj instanceof GeneralConfig) {
            GeneralConfig that = (GeneralConfig) obj;
            // intentionally leaving off version since it represents the prior version hash when
            // sending to the server, and represents the current version hash when receiving from
            // the server
            return Objects.equal(enabled, that.enabled)
                    && Objects.equal(storeThresholdMillis, that.storeThresholdMillis)
                    && Objects.equal(stuckThresholdSeconds, that.stuckThresholdSeconds)
                    && Objects.equal(maxSpans, that.maxSpans)
                    && Objects.equal(threadInfoEnabled, that.threadInfoEnabled)
                    && Objects.equal(gcInfoEnabled, that.gcInfoEnabled);
        }
        return false;
    }

    @Override
    public int hashCode() {
        // intentionally leaving off version since it represents the prior version hash when
        // sending to the server, and represents the current version hash when receiving from the
        // server
        return Objects.hashCode(enabled, storeThresholdMillis, stuckThresholdSeconds, maxSpans,
                threadInfoEnabled, gcInfoEnabled);
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("enabled", enabled)
                .add("storeThresholdMillis", storeThresholdMillis)
                .add("stuckThresholdSeconds", stuckThresholdSeconds)
                .add("maxSpans", maxSpans)
                .add("threadInfoEnabled", threadInfoEnabled)
                .add("gcInfoEnabled", gcInfoEnabled)
                .add("version", version)
                .toString();
    }

    @JsonCreator
    static GeneralConfig readValue(
            @JsonProperty("enabled") @Nullable Boolean enabled,
            @JsonProperty("storeThresholdMillis") @Nullable Integer storeThresholdMillis,
            @JsonProperty("stuckThresholdSeconds") @Nullable Integer stuckThresholdSeconds,
            @JsonProperty("maxSpans") @Nullable Integer maxSpans,
            @JsonProperty("threadInfoEnabled") @Nullable Boolean threadInfoEnabled,
            @JsonProperty("gcInfoEnabled") @Nullable Boolean gcInfoEnabled,
            @JsonProperty("version") @Nullable String version) throws JsonMappingException {
        checkRequiredProperty(enabled, "enabled");
        checkRequiredProperty(storeThresholdMillis, "storeThresholdMillis");
        checkRequiredProperty(stuckThresholdSeconds, "stuckThresholdSeconds");
        checkRequiredProperty(maxSpans, "maxSpans");
        checkRequiredProperty(threadInfoEnabled, "threadInfoEnabled");
        checkRequiredProperty(gcInfoEnabled, "gcInfoEnabled");
        checkRequiredProperty(version, "version");
        GeneralConfig config = new GeneralConfig(version);
        config.setEnabled(enabled);
        config.setStoreThresholdMillis(storeThresholdMillis);
        config.setStuckThresholdSeconds(stuckThresholdSeconds);
        config.setMaxSpans(maxSpans);
        config.setThreadInfoEnabled(threadInfoEnabled);
        config.setGcInfoEnabled(gcInfoEnabled);
        return config;
    }
}
