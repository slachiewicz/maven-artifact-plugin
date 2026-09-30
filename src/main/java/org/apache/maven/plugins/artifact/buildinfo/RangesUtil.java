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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.apache.maven.api.ArtifactCoordinates;
import org.apache.maven.api.Dependency;
import org.apache.maven.api.DependencyCoordinates;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.VersionConstraint;
import org.apache.maven.api.VersionRange;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.ProjectBuilder;
import org.apache.maven.api.services.ProjectBuilderRequest;

/**
 * Utility class to find dependencies with version ranges in the project.
 * <p>
 * The Maven 4 API does not keep the version constraint of a dependency in the dependency graph: the ranges of the
 * project are read from its dependencies, and the ones of the transitive dependencies from the POM of each
 * dependency of the graph.
 */
public class RangesUtil {

    /**
     * A dependency that has been resolved from a version range.
     *
     * @param groupId the groupId of the resolved dependency
     * @param artifactId the artifactId of the resolved dependency
     * @param version the version the range has been resolved to
     * @param message the description of the dependency and of its range
     */
    public record RangeDependency(String groupId, String artifactId, String version, String message) {}

    private RangesUtil() {}

    /**
     * Finds all dependencies with version ranges in the project.
     *
     * @param session the Maven session
     * @param project the Maven project to analyze
     * @return the dependencies resolved from a version range
     * @throws MojoException if dependency collection fails
     */
    public static List<RangeDependency> findVersionRangeDependencies(Session session, Project project)
            throws MojoException {
        Node root;
        try {
            root = session.collectDependencies(project, PathScope.TEST_RUNTIME);
        } catch (RuntimeException e) {
            throw new MojoException("Cannot build dependency tree: " + e.getMessage(), e);
        }
        List<RangeDependency> result = new ArrayList<>();
        walk(
                session,
                root,
                project.getDependencies(),
                project.getManagedDependencies(),
                new LinkedList<>(),
                new HashMap<>(),
                result);
        return result;
    }

    private static void walk(
            Session session,
            Node parent,
            List<DependencyCoordinates> declared,
            List<DependencyCoordinates> managed,
            LinkedList<Node> ancestors,
            Map<String, List<DependencyCoordinates>> cache,
            List<RangeDependency> result) {
        for (Node child : parent.getChildren()) {
            Dependency dependency = child.getDependency();
            if (dependency == null) {
                continue;
            }
            // the dependency management of the project applies to every dependency, it replaces the version a
            // POM declares, range or not
            DependencyCoordinates coordinates = find(managed, dependency);
            if (coordinates == null) {
                coordinates = find(declared, dependency);
            }
            if (coordinates != null && isVersionRange(coordinates.getVersionConstraint())) {
                String path = "";
                if (!ancestors.isEmpty()) {
                    // from the direct parent, up to the child of the project
                    path = " via "
                            + ancestors.stream()
                                    .map(n -> describe(n.getDependency(), false))
                                    .collect(Collectors.joining(" --> "));
                }
                result.add(new RangeDependency(
                        dependency.getGroupId(),
                        dependency.getArtifactId(),
                        dependency.getVersion().toString(),
                        "Dependency " + describe(dependency, true) + path + " has been resolved from a version range "
                                + coordinates.getVersionConstraint()));
            }

            if (!child.getChildren().isEmpty()) {
                ancestors.addFirst(child);
                walk(session, child, dependenciesOf(session, dependency, cache), managed, ancestors, cache, result);
                ancestors.removeFirst();
            }
        }
    }

    private static String describe(Dependency dependency, boolean withScope) {
        String artifact = dependency.getGroupId()
                + ':'
                + dependency.getArtifactId()
                + ':'
                + dependency.getExtension()
                + (dependency.getClassifier().isEmpty() ? "" : ":" + dependency.getClassifier())
                + ':'
                + dependency.getVersion();
        return withScope
                ? artifact + " (" + dependency.getScope().id() + (dependency.isOptional() ? "?" : "") + ")"
                : artifact;
    }

    private static DependencyCoordinates find(List<DependencyCoordinates> declared, Dependency dependency) {
        for (DependencyCoordinates candidate : declared) {
            if (candidate.getGroupId().equals(dependency.getGroupId())
                    && candidate.getArtifactId().equals(dependency.getArtifactId())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * @return the dependencies the POM of a dependency declares, or an empty list if the POM cannot be read
     */
    private static List<DependencyCoordinates> dependenciesOf(
            Session session, Dependency dependency, Map<String, List<DependencyCoordinates>> cache) {
        String key = dependency.getGroupId() + ':' + dependency.getArtifactId() + ':' + dependency.getVersion();
        return cache.computeIfAbsent(key, k -> {
            try {
                ArtifactCoordinates pom = session.createArtifactCoordinates(
                        dependency.getGroupId(),
                        dependency.getArtifactId(),
                        dependency.getVersion().toString(),
                        "pom");
                Path pomPath = session.resolveArtifact(pom).getPath();
                return session.getService(ProjectBuilder.class)
                        .build(ProjectBuilderRequest.builder()
                                .session(session)
                                .path(pomPath)
                                .processPlugins(false)
                                .build())
                        .getProject()
                        .map(Project::getDependencies)
                        .orElse(List.of());
            } catch (RuntimeException e) {
                return List.of();
            }
        });
    }

    private static boolean isVersionRange(VersionConstraint versionConstraint) {
        if (versionConstraint == null) {
            return false;
        }

        if (versionConstraint.getRecommendedVersion() != null) {
            String version = versionConstraint.getRecommendedVersion().toString();
            return version.equals("LATEST") || version.equals("RELEASE");
        }

        VersionRange range = versionConstraint.getVersionRange();
        if (range != null) {
            return !Objects.equals(
                    range.getLowerBoundary() == null
                            ? null
                            : range.getLowerBoundary().getVersion(),
                    range.getUpperBoundary() == null
                            ? null
                            : range.getUpperBoundary().getVersion());
        }
        return false;
    }
}
