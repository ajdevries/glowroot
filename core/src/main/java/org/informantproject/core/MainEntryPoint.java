/**
 * Copyright 2011-2012 the original author or authors.
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
package org.informantproject.core;

import java.lang.instrument.Instrumentation;
import java.util.List;

import org.informantproject.api.PluginServices;
import org.informantproject.core.trace.PluginServicesImpl;
import org.informantproject.shaded.aspectj.weaver.loadtime.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.inject.Guice;
import com.google.inject.Injector;

/**
 * This class is registered as the Premain-Class in the MANIFEST.MF of informant-core.jar:
 * 
 * Premain-Class: org.informantproject.core.MainEntryPoint
 * 
 * This defines the entry point when the JVM is launched via -javaagent:informant-core.jar. This
 * class starts various background threads and then starts the AspectJ load-time weaving agent.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class MainEntryPoint {

    private static final Logger logger = LoggerFactory.getLogger(MainEntryPoint.class);

    private static final PluginServicesProxy pluginServicesProxy = new PluginServicesProxy();
    private static volatile Injector injector;
    private static final Object lock = new Object();

    private MainEntryPoint() {}

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        logger.debug("premain(): agentArgs={}", agentArgs);
        start(new AgentArgs(agentArgs));
        // start the AspectJ load-time weaving agent
        setAspectjAopXmlSearchPath();
        Agent.premain(null, instrumentation);
    }

    public static PluginServices getPluginServices() {
        return pluginServicesProxy;
    }

    public static void start() {
        start(new AgentArgs());
    }

    public static void start(String agentArgs) {
        start(new AgentArgs(agentArgs));
    }

    public static void start(AgentArgs agentArgs) {
        logger.debug("start(): classLoader={}", MainEntryPoint.class.getClassLoader());
        synchronized (lock) {
            if (injector != null) {
                throw new IllegalStateException("Informant is already started");
            }
            injector = Guice.createInjector(new InformantModule(agentArgs));
            InformantModule.start(injector);
            pluginServicesProxy.start(injector.getInstance(PluginServicesImpl.class));
        }
    }

    public static void shutdown() {
        logger.debug("shutdown()");
        synchronized (lock) {
            if (injector == null) {
                throw new IllegalStateException("Informant is not started");
            }
            pluginServicesProxy.shutdown();
            InformantModule.shutdown(injector);
            injector = null;
        }
    }

    @VisibleForTesting
    public static void setAspectjAopXmlSearchPath() {
        // when an informant package is created (e.g. informant-for-web), the
        // META-INF/org.informantproject.aop.xml files from each plugin are renamed slightly
        // so that they can coexist with each other inside a single jar
        // (e.g. META-INF/org.informantproject.aop.1.xml, ...)
        List<String> resourceNames = Lists.newArrayList("META-INF/org.informantproject.aop.xml");
        int i = 1;
        while (true) {
            String resourceName = "META-INF/org.informantproject.aop." + i + ".xml";
            if (MainEntryPoint.class.getClassLoader().getResource(resourceName) == null) {
                break;
            } else {
                resourceNames.add(resourceName);
                i++;
            }
        }
        System.setProperty("org.informantproject.shaded.aspectj.weaver.loadtime.configuration",
                Joiner.on(";").join(resourceNames));
    }
}