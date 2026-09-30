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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Model;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.MessageBuilder;
import org.apache.maven.api.services.MessageBuilderFactory;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.api.services.ToolchainManager;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.plugins.artifact.buildinfo.RangesUtil.RangeDependency;
import org.apache.maven.shared.archiver.MavenArchiver;

/**
 * Base buildinfo-generating class, for goals related to Reproducible Builds {@code .buildinfo} files.
 *
 * @since 3.2.0
 */
public abstract class AbstractBuildinfoMojo implements org.apache.maven.api.plugin.Mojo {

    /**
     * Location of the generated buildinfo file.
     */
    @Parameter(
            defaultValue = "${project.build.directory}/${project.artifactId}-${project.version}.buildinfo",
            required = true,
            readonly = true)
    protected Path buildinfoFile;

    /**
     * Ignore javadoc attached artifacts from buildinfo generation.
     */
    @Parameter(property = "buildinfo.ignoreJavadoc", defaultValue = "true")
    private boolean ignoreJavadoc;

    /**
     * Artifacts to ignore, specified as a glob matching against <code>${groupId}/${filename}</code>, for example
     * <code>*</>/*.xml</code>. If all artifacts from a module are to be ignored, prefer {@code skipModules}.
     */
    @Parameter(property = "buildinfo.ignore", defaultValue = "")
    private List<String> ignore;

    /**
     * Auto-detect projects/modules with install or deploy skipped: avoid taking fingerprints.
     * If auto-detection gives unexpected results, prefer handwritten {@code skipModules}.
     */
    @Parameter(property = "buildinfo.detect.skip", defaultValue = "true")
    private boolean detectSkip;

    /**
     * Avoid taking fingerprints for full modules, specified as glob matching against
     * <code>${groupId}/${artifactId}</code>.
     * For ignoring only a few files instead of full modules, see {@code ignore}.
     *
     * @since 3.5.0
     */
    @Parameter(property = "buildinfo.skipModules")
    private List<String> skipModules;

    private List<PathMatcher> skipModulesMatcher = null;

    /**
     * Makes the generated {@code .buildinfo} file reproducible, by dropping detailed environment recording: OS will be
     * recorded as "Windows" or "Unix", JVM version as major version only.
     *
     * @since 3.1.0
     */
    @Parameter(property = "buildinfo.reproducible", defaultValue = "false")
    private boolean reproducible;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 3.2.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    protected String outputTimestamp;

    /**
     * Diagnose {@code outputTimestamp} effective value based on execution context.
     *
     * @since 3.5.2
     */
    @Parameter(property = "diagnose", defaultValue = "false")
    private boolean diagnose;

    /**
     * The Maven project.
     */
    @Inject
    protected Project project;

    /**
     * The current build session instance. This is used for toolchain manager API calls.
     */
    @Inject
    protected Session session;

    @Inject
    protected Log log;

    @Override
    public void execute() throws MojoException {
        boolean mono = session.getProjects().size() == 1;

        hasBadOutputTimestamp(outputTimestamp, log, project, session, diagnose);

        if (!mono) {
            // if module skips install and/or deploy
            if (detectSkip && PluginUtil.isSkip(session, project)) {
                log.info("Auto-skipping goal because module skips install and/or deploy");
                return;
            }
            if (isSkipModule(project)) {
                log.info("Skipping goal for module");
                return;
            }
            // if multi-module build, generate (aggregate) buildinfo only in last module
            Project last = getLastProject();
            if (!isSame(project, last)) {
                skip(last);
                return;
            }
        }

        // generate buildinfo
        Map<OutputArtifact, String> artifacts = generateBuildinfo(mono);
        log.info("Saved " + (mono ? "" : "aggregate ") + "info on build to " + buildinfoFile);

        copyAggregateToRoot(buildinfoFile);

        execute(artifacts);
    }

    /**
     * @return whether two projects are the same project of the build
     */
    static boolean isSame(Project a, Project b) {
        return a == b || (a != null && b != null && a.getId().equals(b.getId()));
    }

    /**
     * @return whether the project is one of the build
     */
    static boolean isInReactor(Session session, Project project) {
        return project != null && session.getProjects().stream().anyMatch(p -> isSame(p, project));
    }

    /**
     * @return the project the build was started from
     */
    static Project getTopLevelProject(Session session) {
        return session.getProjects().stream()
                .filter(Project::isTopProject)
                .findFirst()
                .orElseGet(() -> session.getProjects().get(0));
    }

    /**
     * @return the property of the model read from the POM file, without inheritance nor interpolation
     */
    private static String originalProperty(Session session, Project project, String name) {
        try {
            Model original = session.getService(ModelXmlFactory.class).read(project.getPomPath());
            return original.getProperties().get(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean hasBadOutputTimestamp(
            String outputTimestamp, Log log, Project project, Session session, boolean diagnose) {
        Instant timestamp =
                MavenArchiver.parseBuildOutputTimestamp(outputTimestamp).orElse(null);
        String effective = ((timestamp == null) ? "disabled" : DateTimeFormatter.ISO_INSTANT.format(timestamp));

        if (diagnose) {
            diagnose(outputTimestamp, log, project, session, effective);
        }

        Map<String, String> properties =
                session.getService(ProjectManager.class).getProperties(project);
        if (timestamp == null) {
            log.error("Reproducible Build not activated by project.build.outputTimestamp property: "
                    + "see https://maven.apache.org/guides/mini/guide-reproducible-builds.html");

            String projectProperty = properties.get("project.build.outputTimestamp");
            if (projectProperty != null && projectProperty.startsWith("${git.")) {
                log.error("project.build.outputTimestamp = \"" + projectProperty + "\": isn't Git value set?");
                log.error("Did validate phase run and Git plugin set the value?");
                if (project.getPackaging().id().equals("pom")) {
                    log.error("if using git-commit-id-plugin, <skipPoms>false</skipPoms> may solve the issue.");
                }
            }
            return true;
        }

        if (log.isDebugEnabled()) {
            log.debug("project.build.outputTimestamp = \"" + outputTimestamp + "\" => "
                    + (effective.equals(outputTimestamp) ? "" : (" => " + effective)));
        }

        // check if timestamp defined in a project from reactor: info if it is not the case
        boolean parentInReactor = false;
        Project reactorParent = project;
        while (isInReactor(session, reactorParent.getParent().orElse(null))) {
            parentInReactor = true;
            reactorParent = reactorParent.getParent().get();
        }
        String prop = originalProperty(session, reactorParent, "project.build.outputTimestamp");
        if (prop == null) {
            log.info("<project.build.outputTimestamp> property (= " + outputTimestamp + ") is inherited"
                    + (parentInReactor ? " from outside the reactor" : "") + ", you can override in "
                    + (parentInReactor ? "parent POM from reactor " + reactorParent.getPomPath() : "pom.xml"));
            return false;
        }

        return false;
    }

    static void diagnose(String outputTimestamp, Log log, Project project, Session session, String effective) {
        ProjectManager projectManager = session.getService(ProjectManager.class);
        if (session.getProjects().size() > 1) {
            Project root = getTopLevelProject(session);
            Project first = session.getProjects().get(0);
            Path firstRelative = root.getBasedir().relativize(first.getPomPath());
            Project firstParent = first.getParent().orElse(null);
            log.info("reactor executionRoot = " + root.getId() + (isSame(root, project) ? " (current)" : "")
                    + System.lineSeparator()
                    + "       reactor first = "
                    + (isSame(first, root) ? "executionRoot" : (first.getId() + " @ " + firstRelative))
                    + System.lineSeparator()
                    + "               first.parent = " + ((firstParent == null) ? null : firstParent.getId()));
            if (firstParent != null && isInReactor(session, firstParent)) {
                // should not happen...
                log.warn("reactor first parent = " + firstParent.getId() + " is in reactor");
            }
        }

        log.info("outputTimestamp = " + outputTimestamp
                + (effective.equals(outputTimestamp) ? "" : (" => " + effective)));

        String projectProperty = projectManager.getProperties(project).get("project.build.outputTimestamp");
        String modelProperty = project.getModel().getProperties().get("project.build.outputTimestamp");
        String originalModelProperty = originalProperty(session, project, "project.build.outputTimestamp");

        log.info("plugin outputTimestamp parameter diagnostics:" + System.lineSeparator()
                + "        - plugin outputTimestamp parameter (defaultValue=\"${project.build.outputTimestamp}\") = "
                + outputTimestamp + System.lineSeparator()
                + "        - project.build.outputTimestamp property from project = " + projectProperty
                + System.lineSeparator()
                + "        - project.build.outputTimestamp property from project model = " + modelProperty
                + System.lineSeparator()
                + "        - project.build.outputTimestamp property from project original model = "
                + originalModelProperty);

        if (outputTimestamp == null) {
            return;
        }

        Optional<Project> parent = project.getParent();
        if (parent.isPresent()) {
            StringBuilder sb = new StringBuilder("Inheritance analysis for property:" + System.lineSeparator()
                    + "        - current " + project.getId() + " property = " + projectProperty);
            while (parent.isPresent()) {
                String parentProperty =
                        projectManager.getProperties(parent.get()).get("project.build.outputTimestamp");
                sb.append(System.lineSeparator());
                sb.append("        - " + (isInReactor(session, parent.get()) ? "reactor" : "external") + " parent "
                        + parent.get().getId() + " property = " + parentProperty);
                if (!projectProperty.equals(parentProperty)) {
                    break;
                }
                parent = parent.get().getParent();
            }
            log.info(sb.toString());
        }
    }

    /**
     * Execute after buildinfo has been generated for current build (eventually aggregated).
     *
     * @param artifacts a Map of artifacts added to the build info with their associated property key prefix
     *         (<code>outputs.[#module.].#artifact</code>)
     */
    abstract void execute(Map<OutputArtifact, String> artifacts) throws MojoException;

    protected void skip(Project last) throws MojoException {
        log.info("Skipping intermediate goal run, aggregate will be " + last.getArtifactId());
    }

    protected Path copyAggregateToRoot(Path aggregate) throws MojoException {
        if (session.getProjects().size() == 1) {
            // mono-module, no aggregate file to deal with
            return aggregate;
        }

        // copy aggregate file to root target directory
        Project root = getTopLevelProject(session);
        String name = aggregate.getFileName().toString();
        String extension = name.substring(name.lastIndexOf('.'));
        Path rootCopy = Paths.get(root.getBuild().getDirectory())
                .resolve(root.getArtifactId() + '-' + root.getVersion() + extension);
        try {
            Files.createDirectories(rootCopy.getParent());
            Files.copy(aggregate, rootCopy, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
            log.info("Aggregate " + extension.substring(1) + " copied to " + relative(rootCopy));
        } catch (IOException ioe) {
            throw new MojoException("Could not copy " + aggregate + " to " + rootCopy, ioe);
        }
        return rootCopy;
    }

    protected BuildInfoWriter newBuildInfoWriter(PrintWriter p, boolean mono) {
        BuildInfoWriter bi = new BuildInfoWriter(log, p, mono, session);
        bi.setIgnoreJavadoc(ignoreJavadoc);
        bi.setIgnore(ignore);
        bi.setToolchain(getToolchain());

        return bi;
    }

    /**
     * Generate buildinfo file.
     *
     * @param mono is it a mono-module build?
     * @return a Map of artifacts added to the build info with their associated property key prefix
     *         (<code>outputs.[#module.].#artifact</code>)
     * @throws MojoException if anything goes wrong
     */
    protected Map<OutputArtifact, String> generateBuildinfo(boolean mono) throws MojoException {
        Project root = mono ? project : getTopLevelProject(session);

        try {
            Files.createDirectories(buildinfoFile.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new MojoException("Error creating directory of " + buildinfoFile, e);
        }

        List<Project> mavenProjects = getProjectListForBuildInfo(mono);
        Set<RangeDependency> versionRangeDependencies = getVersionRangeDependencies(mavenProjects);

        try (PrintWriter p = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(buildinfoFile), StandardCharsets.UTF_8)))) {
            BuildInfoWriter bi = newBuildInfoWriter(p, mono);
            bi.printHeader(root, mono ? null : project, versionRangeDependencies, reproducible);

            // artifact(s) fingerprints
            for (Project project : mavenProjects) {
                bi.printArtifacts(project);
            }

            if (p.checkError()) {
                throw new MojoException("Write error to " + buildinfoFile);
            }

            return bi.getArtifacts();
        } catch (IOException e) {
            throw new MojoException("Error creating file " + buildinfoFile, e);
        }
    }

    private List<Project> getProjectListForBuildInfo(boolean mono) {
        if (mono) {
            return Collections.singletonList(project);
        } else {
            return session.getProjects().stream().filter(p -> !isSkip(p)).collect(Collectors.toList());
        }
    }

    private Set<RangeDependency> getVersionRangeDependencies(List<Project> mavenProjects) throws MojoException {
        Set<RangeDependency> dependencies = new LinkedHashSet<>();
        for (Project project : mavenProjects) {
            dependencies.addAll(RangesUtil.findVersionRangeDependencies(session, project));
        }

        return dependencies;
    }

    /**
     * @return a builder of messages, which are colored when the console is
     */
    protected MessageBuilder buffer() {
        return session.getService(MessageBuilderFactory.class).builder();
    }

    protected Project getLastProject() {
        int i = session.getProjects().size();
        while (i > 0) {
            Project project = session.getProjects().get(--i);
            if (!isSkip(project)) {
                return project;
            }
        }
        return null;
    }

    protected boolean isSkip(Project project) {
        return isSkipModule(project) || (detectSkip && PluginUtil.isSkip(session, project));
    }

    protected boolean isSkipModule(Project project) {
        if (skipModules == null || skipModules.isEmpty()) {
            return false;
        }
        if (skipModulesMatcher == null) {
            FileSystem fs = FileSystems.getDefault();
            skipModulesMatcher = skipModules.stream()
                    .map(i -> fs.getPathMatcher("glob:" + i))
                    .collect(Collectors.toList());
        }
        Path path = Paths.get(project.getGroupId() + '/' + project.getArtifactId());
        return skipModulesMatcher.stream().anyMatch(m -> m.matches(path));
    }

    private Toolchain getToolchain() {
        return session.getService(ToolchainManager.class)
                .getToolchainFromBuildContext(session, "jdk")
                .orElse(null);
    }

    protected String relative(Path file) {
        Path basedir = getTopLevelProject(session).getBasedir();
        return basedir.relativize(file.toAbsolutePath()).toString();
    }
}
