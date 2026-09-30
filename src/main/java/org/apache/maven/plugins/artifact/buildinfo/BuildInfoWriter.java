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

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Toolchain;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.ProjectManager;

/**
 * Buildinfo content writer.
 */
class BuildInfoWriter {
    private final Log log;
    private final PrintWriter p;
    private final boolean mono;
    private final Session session;
    private final Map<OutputArtifact, String> artifacts = new LinkedHashMap<>();
    private int projectCount = -1;
    private boolean ignoreJavadoc = true;
    private List<PathMatcher> ignore;
    private Toolchain toolchain;

    BuildInfoWriter(Log log, PrintWriter p, boolean mono, Session session) {
        this.log = log;
        this.p = p;
        this.mono = mono;
        this.session = session;
    }

    void printHeader(Project project, Project aggregate, String rangeFilter, boolean reproducible) {
        p.println("# https://reproducible-builds.org/docs/jvm/");
        p.println("buildinfo.version=1.0-SNAPSHOT");
        p.println();
        p.println("name=" + project.getModel().getName());
        p.println("group-id=" + project.getGroupId());
        p.println("artifact-id=" + project.getArtifactId());
        p.println("version=" + project.getVersion());
        p.println();
        printSourceInformation(project);
        p.println();
        p.println("# build instructions");
        p.println("build-tool=mvn");
        // p.println( "# optional build setup url, as plugin parameter" );
        p.println();
        if (reproducible) {
            p.println("# build environment information (simplified for reproducibility)");
            p.println("java.version=" + extractJavaMajorVersion(System.getProperty("java.version")));
            String ls = System.lineSeparator();
            p.println("os.name=" + ("\n".equals(ls) ? "Unix" : "Windows"));
        } else {
            p.println("# effective build environment information");
            p.println("java.version=" + System.getProperty("java.version"));
            p.println("java.vendor=" + System.getProperty("java.vendor"));
            p.println("os.name=" + System.getProperty("os.name"));
            p.println("os.version=" + System.getProperty("os.version"));
            p.println("os.arch=" + System.getProperty("os.arch"));
            p.println("line.separator="
                    + System.lineSeparator().replace("\r", "\\r").replace("\n", "\\n"));
        }
        p.println();
        p.println("# Maven rebuild instructions and effective environment");
        if (!reproducible) {
            p.println("mvn.version=" + session.getMavenVersion());
        }
        if ((project.getModel().getPrerequisites() != null)
                && (project.getModel().getPrerequisites().getMaven() != null)) {
            // TODO wrong algorithm, should reuse algorithm written in versions-maven-plugin
            p.println("mvn.minimum.version="
                    + project.getModel().getPrerequisites().getMaven());
        }

        if (!rangeFilter.isEmpty()) {
            p.println("mvn.rebuild-args=-Dmaven.session.versionFilter=" + rangeFilter);
        }

        if (toolchain != null) {
            String javaVersion = JdkToolchainUtil.getJavaVersion(toolchain);
            if (reproducible) {
                javaVersion = extractJavaMajorVersion(javaVersion);
            }
            p.println("mvn.toolchain.jdk=" + javaVersion);
        }

        if (!mono && (aggregate != null)) {
            p.println("mvn.aggregate.artifact-id=" + aggregate.getArtifactId());
        }

        p.println();
        p.println("# " + (mono ? "" : "aggregated ") + "output");
    }

    private static String extractJavaMajorVersion(String javaVersion) {
        if (javaVersion.startsWith("1.")) {
            javaVersion = javaVersion.substring(2);
        }
        int index = javaVersion.indexOf('.'); // for example 8.0_202
        if (index < 0) {
            index = javaVersion.indexOf('-'); // for example 17-ea
        }
        return (index < 0) ? javaVersion : javaVersion.substring(0, index);
    }

    private void printSourceInformation(Project project) {
        boolean sourceAvailable = false;
        p.println("# source information");
        // p.println( "# TBD source.* artifact, url should be parameters" );
        if (project.getModel().getScm() != null) {
            sourceAvailable = true;
            p.println("source.scm.uri=" + project.getModel().getScm().getConnection());
            p.println("source.scm.tag=" + project.getModel().getScm().getTag());
        } else {
            p.println("# no scm configured in pom.xml");
        }

        if (!sourceAvailable) {
            log.warn("No source information available in buildinfo for rebuilders...");
        }
    }

    void printArtifacts(Project project) throws MojoException {
        String prefix = "outputs.";
        if (!mono) {
            // aggregated buildinfo output
            projectCount++;
            prefix += projectCount + ".";
            p.println();
            p.println(prefix + "coordinates=" + project.getGroupId() + ':' + project.getArtifactId());
        }

        ProjectManager projectManager = session.getService(ProjectManager.class);
        Collection<ProducedArtifact> attachedArtifacts = projectManager.getAttachedArtifacts(project);

        // detect Maven 4 consumer POM transient attachment
        OutputArtifact consumerPom = attachedArtifacts.stream()
                .filter(a -> "pom".equals(a.getExtension()) && "consumer".equals(a.getClassifier()))
                .findAny()
                .map(a -> OutputArtifact.of(a, session.getArtifactPath(a).orElse(null)))
                .orElse(null);

        int n = 0;
        OutputArtifact pomArtifact = new OutputArtifact(
                project.getGroupId(), project.getArtifactId(), project.getVersion(), "", "pom", null);
        if (consumerPom != null) {
            // Maven 4 transient consumer POM attachment is published as the POM, overrides build POM, see
            // https://github.com/apache/maven/blob/c79a7a02721f0f9fd7e202e99f60b593461ba8cc/maven-core/src/main/java/org/apache/maven/internal/transformation/ConsumerPomArtifactTransformer.java#L130-L155
            try {
                Path pomFile = Files.createTempFile(Paths.get(project.getBuild().getDirectory()), "consumer-", ".pom");
                Files.copy(consumerPom.file(), pomFile, StandardCopyOption.REPLACE_EXISTING);
                pomArtifact = pomArtifact.withFile(pomFile);
            } catch (IOException e) {
                p.println("Error processing consumer POM: " + e);
            }
        } else {
            pomArtifact = pomArtifact.withFile(project.getPomPath());
        }

        artifacts.put(pomArtifact, prefix + n);
        if (isIgnore(pomArtifact)) {
            p.println("# ignored " + pomArtifact.filename());
        } else {
            printFile(
                    prefix + n++,
                    pomArtifact.groupId(),
                    pomArtifact.file(),
                    project.getArtifactId() + '-' + project.getVersion() + ".pom");
        }

        if (consumerPom != null) {
            // build pom
            OutputArtifact buildPomArtifact = new OutputArtifact(
                            project.getGroupId(), project.getArtifactId(), project.getVersion(), "build", "pom", null)
                    .withFile(project.getPomPath());

            if (isIgnore(buildPomArtifact)) {
                p.println("# ignored " + buildPomArtifact.filename());
            } else {
                artifacts.put(buildPomArtifact, prefix + n);
                printFile(
                        prefix + n++,
                        buildPomArtifact.groupId(),
                        buildPomArtifact.file(),
                        project.getArtifactId() + '-' + project.getVersion() + "-build.pom");
            }
        }

        Optional<ProducedArtifact> mainArtifact = project.getMainArtifact();
        if (mainArtifact.isPresent()) {
            Optional<Path> mainFile = session.getArtifactPath(mainArtifact.get());
            if (mainFile.isPresent()) {
                OutputArtifact main = OutputArtifact.of(mainArtifact.get(), mainFile.get());
                if (isIgnore(main)) {
                    p.println("# ignored " + main.filename());
                } else {
                    printArtifact(prefix, n++, main);
                }
            }
        }

        for (ProducedArtifact attachedArtifact : attachedArtifacts) {
            OutputArtifact attached = OutputArtifact.of(
                    attachedArtifact, session.getArtifactPath(attachedArtifact).orElse(null));
            if ("pom".equals(attached.extension()) && "consumer".equals(attached.classifier())) {
                // ignore consumer pom
                continue;
            }
            if (attached.extension().endsWith(".asc")) {
                // ignore pgp signatures
                continue;
            }
            if (ignoreJavadoc && "javadoc".equals(attached.classifier())) {
                // TEMPORARY ignore javadoc, waiting for MJAVADOC-627 in m-javadoc-p 3.2.0
                continue;
            }
            if (isIgnore(attached)) {
                p.println("# ignored " + attached.filename());
                artifacts.put(attached, null);
                continue;
            }
            printArtifact(prefix, n++, attached);
        }
    }

    private void printArtifact(String prefix, int i, OutputArtifact artifact) throws MojoException {
        prefix = prefix + i;
        Path artifactFile = artifact.file();
        if (artifactFile == null) {
            log.warn("Ignoring artifact " + artifact.id() + " because it has no file");
            return;
        }
        if (Files.isDirectory(artifactFile)) {
            if ("pom".equals(artifact.extension())) {
                // ignore .pom files: they should not be treated as Artifacts
                return;
            }
            // edge case found in a distribution module with default packaging and skip set for
            // m-jar-p: should use pom packaging instead
            throw new MojoException("Artifact " + artifact.id() + " points to a directory: " + artifactFile
                    + ". Packaging should be 'pom'?");
        }
        if (!Files.isRegularFile(artifactFile)) {
            log.warn("Ignoring artifact " + artifact.id() + " because it points to inexistent " + artifactFile);
            return;
        }

        printFile(prefix, artifact.groupId(), artifact.file(), artifact.filename());
        artifacts.put(artifact, prefix);
    }

    static String getArtifactFilename(OutputArtifact artifact) {
        return artifact.filename();
    }

    void printFile(String prefix, String groupId, Path file) throws MojoException {
        printFile(prefix, groupId, file, file.getFileName().toString());
    }

    private void printFile(String prefix, String groupId, Path file, String filename) throws MojoException {
        p.println();
        p.println(prefix + ".groupId=" + groupId);
        p.println(prefix + ".filename=" + filename);
        try {
            p.println(prefix + ".length=" + Files.size(file));
            try (InputStream is = Files.newInputStream(file)) {
                p.println(prefix + ".checksums.sha512=" + DigestUtils.sha512Hex(is));
            }
        } catch (IOException ioe) {
            throw new MojoException("Error processing file " + file, ioe);
        } catch (IllegalArgumentException iae) {
            throw new MojoException("Could not get hash algorithm", iae.getCause());
        }
    }

    Map<OutputArtifact, String> getArtifacts() {
        return artifacts;
    }

    /**
     * Load buildinfo file and extracts properties on output files.
     *
     * @param buildinfo the build info file
     * @return output properties
     * @throws MojoException
     */
    static Properties loadOutputProperties(Path buildinfo) throws MojoException {
        Properties prop = new Properties();
        if (buildinfo != null) {
            try (InputStream is = Files.newInputStream(buildinfo)) {
                prop.load(is);
            } catch (IOException e) {
                // silent
            }
        }
        for (String name : prop.stringPropertyNames()) {
            if (!name.startsWith("outputs.") || name.endsWith(".coordinates")) {
                prop.remove(name);
            }
        }
        return prop;
    }

    boolean getIgnoreJavadoc() {
        return ignoreJavadoc;
    }

    void setIgnoreJavadoc(boolean ignoreJavadoc) {
        this.ignoreJavadoc = ignoreJavadoc;
    }

    void setIgnore(List<String> ignore) {
        FileSystem fs = FileSystems.getDefault();
        this.ignore = ignore.stream().map(i -> fs.getPathMatcher("glob:" + i)).collect(Collectors.toList());
    }

    boolean isIgnore(OutputArtifact attached) {
        Path path = Paths.get(attached.groupId() + '/' + attached.filename());
        return ignore.stream().anyMatch(m -> m.matches(path));
    }

    public void setToolchain(Toolchain toolchain) {
        this.toolchain = toolchain;
    }
}
