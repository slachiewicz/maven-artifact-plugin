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

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.DependencyScope;
import org.apache.maven.api.DownloadedArtifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.VersionConstraint;
import org.apache.maven.api.VersionRange;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderRequest;
import org.apache.maven.api.services.ProjectBuilderResult;
import org.apache.maven.plugins.artifact.buildinfo.RangesUtil.RangeDependency;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RangesUtilTest {

    private final Session session = mock(Session.class);

    private final Project project = mock(Project.class);

    private final Node root = mock(Node.class);

    @BeforeEach
    void setup() {
        when(session.collectDependencies(project, PathScope.TEST_RUNTIME)).thenReturn(root);
    }

    private static Version version(String v) {
        Version version = mock(Version.class);
        when(version.toString()).thenReturn(v);
        return version;
    }

    private static Dependency dependency(String groupId, String artifactId, String version) {
        Dependency dependency = mock(Dependency.class);
        when(dependency.getGroupId()).thenReturn(groupId);
        when(dependency.getArtifactId()).thenReturn(artifactId);
        Version resolved = version(version);
        when(dependency.getVersion()).thenReturn(resolved);
        when(dependency.getExtension()).thenReturn("jar");
        when(dependency.getClassifier()).thenReturn("");
        DependencyScope scope = mock(DependencyScope.class);
        when(scope.id()).thenReturn("compile");
        when(dependency.getScope()).thenReturn(scope);
        return dependency;
    }

    private static Node node(Dependency dependency, Node... children) {
        Node node = mock(Node.class);
        when(node.getDependency()).thenReturn(dependency);
        when(node.getChildren()).thenReturn(List.of(children));
        return node;
    }

    private static DependencyCoordinates declared(String groupId, String artifactId, VersionConstraint constraint) {
        DependencyCoordinates coordinates = mock(DependencyCoordinates.class);
        when(coordinates.getGroupId()).thenReturn(groupId);
        when(coordinates.getArtifactId()).thenReturn(artifactId);
        when(coordinates.getVersionConstraint()).thenReturn(constraint);
        return coordinates;
    }

    private static VersionConstraint range(String lower, String upper, String text) {
        VersionConstraint constraint = mock(VersionConstraint.class);
        VersionRange range = mock(VersionRange.class);
        VersionRange.Boundary lowerBoundary = mock(VersionRange.Boundary.class);
        VersionRange.Boundary upperBoundary = mock(VersionRange.Boundary.class);
        Version lowerVersion = version(lower);
        // mocks are equal only when they are the same instance
        Version upperVersion = upper.equals(lower) ? lowerVersion : version(upper);
        when(lowerBoundary.getVersion()).thenReturn(lowerVersion);
        when(upperBoundary.getVersion()).thenReturn(upperVersion);
        when(range.getLowerBoundary()).thenReturn(lowerBoundary);
        when(range.getUpperBoundary()).thenReturn(upperBoundary);
        when(constraint.getVersionRange()).thenReturn(range);
        when(constraint.toString()).thenReturn(text);
        return constraint;
    }

    private static VersionConstraint fixed(String v) {
        VersionConstraint constraint = mock(VersionConstraint.class);
        Version recommended = version(v);
        when(constraint.getRecommendedVersion()).thenReturn(recommended);
        return constraint;
    }

    @Test
    void rangeOfADirectDependency() {
        Node commonsIo = node(dependency("commons-io", "commons-io", "2.21.0"));
        Node lang = node(dependency("org.apache.commons", "commons-lang3", "3.20.0"));
        Node collections = node(dependency("commons-collections", "commons-collections", "LATEST"));
        when(root.getChildren()).thenReturn(List.of(commonsIo, lang, collections));
        List<DependencyCoordinates> declared = List.of(
                declared("commons-io", "commons-io", range("2.20.0", "2.21.0", "[2.20.0,2.21.0]")),
                // a range with the same bounds is a fixed version
                declared("org.apache.commons", "commons-lang3", range("3.20.0", "3.20.0", "[3.20.0]")),
                declared("commons-collections", "commons-collections", fixed("LATEST")));
        when(project.getDependencies()).thenReturn(declared);

        List<RangeDependency> result = RangesUtil.findVersionRangeDependencies(session, project);

        assertEquals(2, result.size());
        assertEquals("commons-io", result.get(0).artifactId());
        assertEquals("2.21.0", result.get(0).version());
        assertEquals(
                "Dependency commons-io:commons-io:jar:2.21.0 (compile) has been resolved from a version range "
                        + "[2.20.0,2.21.0]",
                result.get(0).message());
        assertTrue(result.get(1)
                .message()
                .endsWith("commons-collections:commons-collections:jar:LATEST (compile) "
                        + "has been resolved from a version range "
                        + result.get(1)
                                .message()
                                .substring(result.get(1).message().lastIndexOf("range ") + 6)));
    }

    @Test
    void dependencyManagementReplacesTheRange() {
        Node commonsIo = node(dependency("commons-io", "commons-io", "2.21.0"));
        when(root.getChildren()).thenReturn(List.of(commonsIo));
        List<DependencyCoordinates> declared =
                List.of(declared("commons-io", "commons-io", range("2.20.0", "2.21.0", "[2.20.0,2.21.0]")));
        when(project.getDependencies()).thenReturn(declared);
        List<DependencyCoordinates> managed = List.of(declared("commons-io", "commons-io", fixed("2.21.0")));
        when(project.getManagedDependencies()).thenReturn(managed);

        assertTrue(RangesUtil.findVersionRangeDependencies(session, project).isEmpty());
    }

    @Test
    void rangeOfATransitiveDependencyComesFromThePomOfItsParent() throws Exception {
        Dependency messagesDependency = dependency("io.cucumber", "messages", "32.1.0");
        Dependency gherkinDependency = dependency("io.cucumber", "gherkin", "38.0.0");
        Node messages = node(messagesDependency);
        Node gherkin = node(gherkinDependency, messages);
        when(root.getChildren()).thenReturn(List.of(gherkin));
        List<DependencyCoordinates> declared = List.of(declared("io.cucumber", "gherkin", fixed("38.0.0")));
        when(project.getDependencies()).thenReturn(declared);

        // the POM of gherkin declares a range for messages
        ArtifactCoordinates pomCoordinates = mock(ArtifactCoordinates.class);
        when(session.createArtifactCoordinates("io.cucumber", "gherkin", "38.0.0", "pom"))
                .thenReturn(pomCoordinates);
        DownloadedArtifact pom = mock(DownloadedArtifact.class);
        when(pom.getPath()).thenReturn(Path.of("gherkin-38.0.0.pom"));
        when(session.resolveArtifact(pomCoordinates)).thenReturn(pom);
        Project gherkinProject = mock(Project.class);
        List<DependencyCoordinates> gherkinDeclared =
                List.of(declared("io.cucumber", "messages", range("32.0.0", "33.0.0", "[32.0.0,33.0.0)")));
        when(gherkinProject.getDependencies()).thenReturn(gherkinDeclared);
        ProjectBuilderResult built = mock(ProjectBuilderResult.class);
        when(built.getProject()).thenReturn(Optional.of(gherkinProject));
        ProjectBuilder builder = mock(ProjectBuilder.class);
        when(builder.build(any(ProjectBuilderRequest.class))).thenReturn(built);
        when(session.getService(ProjectBuilder.class)).thenReturn(builder);

        List<RangeDependency> result = RangesUtil.findVersionRangeDependencies(session, project);

        assertEquals(1, result.size());
        assertEquals(
                "Dependency io.cucumber:messages:jar:32.1.0 (compile) via io.cucumber:gherkin:jar:38.0.0 "
                        + "has been resolved from a version range [32.0.0,33.0.0)",
                result.get(0).message());
    }

    @Test
    void unreadablePomOfADependencyHidesItsRanges() {
        Node child = node(dependency("g", "child", "1.0"));
        Node parent = node(dependency("g", "parent", "1.0"), child);
        when(root.getChildren()).thenReturn(List.of(parent));
        List<DependencyCoordinates> declared = List.of(declared("g", "parent", fixed("1.0")));
        when(project.getDependencies()).thenReturn(declared);
        when(session.createArtifactCoordinates("g", "parent", "1.0", "pom")).thenThrow(new RuntimeException("offline"));

        assertTrue(RangesUtil.findVersionRangeDependencies(session, project).isEmpty());
    }
}
