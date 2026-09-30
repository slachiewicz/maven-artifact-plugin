/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.artifact.buildinfo;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.apache.maven.api.Lifecycle;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.LifecycleRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CheckBuildPlanMojoTest {

    private final Session session = mock(Session.class);

    private final Project project = mock(Project.class);

    private final CheckBuildPlanMojo mojo = new CheckBuildPlanMojo();

    @BeforeEach
    void setup() throws Exception {
        Lifecycle lifecycle = mock(Lifecycle.class);
        LifecycleRegistry registry = mock(LifecycleRegistry.class);
        when(registry.iterator()).thenAnswer(i -> List.of(lifecycle).iterator());
        when(registry.computePhases(lifecycle))
                .thenReturn(List.of("validate", "compile", "package", "install", "deploy"));
        when(session.getService(LifecycleRegistry.class)).thenReturn(registry);

        set("session", session);
        set("project", project);
        set("tasks", new String[] {"package"});
    }

    private void set(String name, Object value) throws Exception {
        Field field = CheckBuildPlanMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static Plugin plugin(String artifactId, String phase, String... goals) {
        return Plugin.newBuilder()
                .groupId("org.apache.maven.plugins")
                .artifactId(artifactId)
                .version("1.0")
                .executions(List.of(PluginExecution.newBuilder()
                        .phase(phase)
                        .goals(List.of(goals))
                        .build()))
                .build();
    }

    private void build(Plugin... plugins) {
        when(project.getModel())
                .thenReturn(Model.newBuilder()
                        .build(Build.newBuilder().plugins(List.of(plugins)).build())
                        .build());
    }

    @Test
    void pluginsOfThePhasesUpToTheTaskAreInThePlan() throws Exception {
        build(
                plugin("maven-compiler-plugin", "compile", "compile"),
                plugin("maven-jar-plugin", "package", "jar"),
                plugin("maven-install-plugin", "install", "install"));

        Map<String, Plugin> plan = mojo.calculatePlugins();

        assertEquals(
                List.of(
                        "org.apache.maven.plugins:maven-compiler-plugin:1.0",
                        "org.apache.maven.plugins:maven-jar-plugin:1.0"),
                List.copyOf(plan.keySet()));
    }

    @Test
    void executionInAMavenThreePhaseNameIsInThePlan() throws Exception {
        Lifecycle lifecycle =
                session.getService(LifecycleRegistry.class).iterator().next();
        Lifecycle.Alias alias = mock(Lifecycle.Alias.class);
        when(alias.v3Phase()).thenReturn("process-resources");
        when(alias.v4Phase()).thenReturn("compile");
        when(lifecycle.aliases()).thenReturn(List.of(alias));
        build(plugin("maven-resources-plugin", "process-resources", "resources"));

        assertTrue(mojo.calculatePlugins().containsKey("org.apache.maven.plugins:maven-resources-plugin:1.0"));
    }

    @Test
    void executionWithoutPhaseIsInThePlan() throws Exception {
        build(plugin("maven-enforcer-plugin", null, "enforce"));

        assertTrue(mojo.calculatePlugins().containsKey("org.apache.maven.plugins:maven-enforcer-plugin:1.0"));
    }

    @Test
    void pluginWithoutGoalIsNotInThePlan() throws Exception {
        build(plugin("maven-compiler-plugin", "compile"));

        assertTrue(mojo.calculatePlugins().isEmpty());
    }

    @Test
    void unknownPhaseIsAnError() throws Exception {
        build();
        set("tasks", new String[] {"nonexistent"});

        assertThrows(MojoException.class, mojo::calculatePlugins);
    }
}
