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

import java.util.Map;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginManagement;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.xml.XmlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginUtilTest {

    private final Session session = mock(Session.class);

    private final ProjectManager projectManager = mock(ProjectManager.class);

    private final Project project = mock(Project.class);

    private Map<String, String> properties = Map.of();

    @BeforeEach
    void setup() {
        when(session.getService(ProjectManager.class)).thenReturn(projectManager);
        when(projectManager.getProperties(project)).thenAnswer(i -> properties);
    }

    private static Plugin plugin(String artifactId, String skipParameter, String skipValue) {
        Plugin.Builder builder =
                Plugin.newBuilder().groupId("org.apache.maven.plugins").artifactId(artifactId);
        if (skipParameter != null) {
            builder.configuration(XmlNode.newInstance(
                    "configuration", java.util.List.of(XmlNode.newInstance(skipParameter, skipValue))));
        }
        return builder.build();
    }

    private void build(Plugin... plugins) {
        when(project.getBuild())
                .thenReturn(
                        Build.newBuilder().plugins(java.util.List.of(plugins)).build());
    }

    @Test
    void installAndDeployAreNotSkippedByDefault() {
        build();

        assertFalse(PluginUtil.isSkip(session, project));
    }

    @Test
    void skipParameterOfThePluginConfiguration() {
        build(plugin("maven-deploy-plugin", "skip", "true"));

        assertTrue(PluginUtil.isSkip(session, project));
    }

    @Test
    void skipPropertyWhenThePluginDoesNotConfigureIt() {
        build();
        properties = Map.of("maven.install.skip", "true");

        assertTrue(PluginUtil.isSkip(session, project));
    }

    @Test
    void pluginManagementIsLookedAt() {
        when(project.getBuild())
                .thenReturn(Build.newBuilder()
                        .pluginManagement(PluginManagement.newBuilder()
                                .plugins(java.util.List.of(plugin("maven-install-plugin", "skip", "true")))
                                .build())
                        .build());

        assertTrue(PluginUtil.isSkip(session, project));
    }

    @Test
    void centralPublishingReplacesInstallAndDeploy() {
        Plugin central = Plugin.newBuilder()
                .groupId("org.sonatype.central")
                .artifactId("central-publishing-maven-plugin")
                .configuration(XmlNode.newInstance(
                        "configuration", java.util.List.of(XmlNode.newInstance("skipPublishing", "true"))))
                .build();
        build(central, plugin("maven-deploy-plugin", "skip", "false"));

        assertTrue(PluginUtil.isSkip(session, project));
    }

    @Test
    void projectWithoutBuildIsNotSkipped() {
        when(project.getBuild()).thenReturn(null);

        assertFalse(PluginUtil.isSkip(session, project));
    }
}
