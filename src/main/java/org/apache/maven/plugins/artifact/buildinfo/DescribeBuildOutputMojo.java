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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.api.ProducedArtifact;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.shared.archiver.MavenArchiver;

/**
 * Describe build output (experimental).
 * It is expected to be used aggregator used from CLI; that is, run at root after everything has run, but not bound to any build
 * phase, where it would be run at root before modules.
 * @since 3.5.2
 */
@Mojo(name = "describe-build-output", aggregator = true)
public class DescribeBuildOutputMojo extends AbstractBuildinfoMojo {

    @Override
    public void execute() throws MojoException {
        // super.execute(); // do not generate buildinfo, just reuse logic from abstract class
        Instant timestamp =
                MavenArchiver.parseBuildOutputTimestamp(outputTimestamp).orElse(null);
        String effective = ((timestamp == null) ? "disabled" : DateTimeFormatter.ISO_INSTANT.format(timestamp));

        diagnose(outputTimestamp, log, project, session, effective);
        log.info("");
        describeBuildEnvironment();
        log.info("");
        describeBuildOutput();
    }

    private Path rootPath;
    private BuildInfoWriter bi;

    private void describeBuildEnvironment() {
        log.info("Build environment information" + System.lineSeparator()
                + "        - java.version=" + System.getProperty("java.version") + System.lineSeparator()
                + "        - java.vendor=" + System.getProperty("java.vendor") + System.lineSeparator()
                + "        - java.home=" + System.getProperty("java.home") + System.lineSeparator()
                + "        - os.name=" + System.getProperty("os.name") + System.lineSeparator()
                + "        - os.version=" + System.getProperty("os.version") + System.lineSeparator()
                + "        - os.arch=" + System.getProperty("os.arch") + System.lineSeparator()
                + "        - mvn.version=" + session.getMavenVersion() + System.lineSeparator()
                + "        - line.separator="
                + System.lineSeparator().replace("\r", "\\r").replace("\n", "\\n"));
    }

    private void describeBuildOutput() throws MojoException {
        rootPath = getTopLevelProject(session).getBasedir();
        bi = newBuildInfoWriter(null, false);
        ProjectManager projectManager = session.getService(ProjectManager.class);

        // the projects are grouped by id, the API does not promise that a project is always the same instance
        Map<String, Project> reactorParentsById = new HashMap<>();
        Map<String, Long> reactorParents = session.getProjects().stream()
                .map(p -> getReactorParent(session, p))
                .peek(p -> reactorParentsById.put(p.getId(), p))
                .collect(Collectors.groupingBy(Project::getId, Collectors.counting()));
        reactorParents.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> {
                    Project parent = reactorParentsById.get(e.getKey());
                    log.info("parent in reactor: " + parent.getGroupId() + ":" + parent.getArtifactId() + " @ "
                            + rootPath.relativize(parent.getPomPath()) + " (" + e.getValue() + " module"
                            + ((e.getValue() > 1) ? "s" : "") + "), property = "
                            + projectManager.getProperties(parent).get("project.build.outputTimestamp"));
                });

        log.info("");

        Map<String, Long> groupIds = session.getProjects().stream()
                .collect(Collectors.groupingBy(Project::getGroupId, Collectors.counting()));
        groupIds.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> log.info("groupId: " + e.getKey() + " (" + e.getValue() + " artifactId"
                        + ((e.getValue() > 1) ? "s" : "") + ")"));

        Map<String, Set<String>> artifactIds = session.getProjects().stream()
                .collect(Collectors.groupingBy(
                        Project::getArtifactId, Collectors.mapping(Project::getGroupId, Collectors.toSet())));
        artifactIds.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(e -> e.getValue().size() > 1)
                .forEach(
                        e -> log.info("artifactId: " + e.getKey() + " defined for multiple groupIds: " + e.getValue()));

        log.info("");
        log.info("skip/ignore? artifactId[:classifier][:extension] = build-path repository-filename size [sha256]");

        for (Project p : session.getProjects()) {
            boolean skipped = isSkip(p);
            String s = skipped ? (isSkipModule(p) ? "skipped      " : "not-deployed ") : "             ";

            Collection<ProducedArtifact> attachedArtifacts = projectManager.getAttachedArtifacts(p);

            // project = pom
            // detect Maven 4 consumer POM transient attachment
            OutputArtifact consumerPom = attachedArtifacts.stream()
                    .filter(a -> "pom".equals(a.getExtension()) && "consumer".equals(a.getClassifier()))
                    .findAny()
                    .map(a -> OutputArtifact.of(a, session.getArtifactPath(a).orElse(null)))
                    .orElse(null);

            OutputArtifact pomArtifact =
                    new OutputArtifact(p.getGroupId(), p.getArtifactId(), p.getVersion(), "", "pom", null);
            if (consumerPom != null) {
                // Maven 4 transient consumer POM attachment is published as the POM, overrides build POM, see
                // https://github.com/apache/maven/blob/c79a7a02721f0f9fd7e202e99f60b593461ba8cc/maven-core/src/main/java/org/apache/maven/internal/transformation/ConsumerPomArtifactTransformer.java#L130-L155
                try {
                    Path pomFile = Files.createTempFile(Paths.get(p.getBuild().getDirectory()), "consumer-", ".pom");
                    Files.copy(consumerPom.file(), pomFile, StandardCopyOption.REPLACE_EXISTING);
                    pomArtifact = pomArtifact.withFile(pomFile);
                    log.info(s + describeArtifact(pomArtifact)); // consumer pom
                    // build pom
                    pomArtifact =
                            new OutputArtifact(p.getGroupId(), p.getArtifactId(), p.getVersion(), "build", "pom", null);
                } catch (IOException e) {
                    throw new MojoException("Error processing consumer POM", e);
                }
            }
            pomArtifact = pomArtifact.withFile(p.getPomPath());
            log.info(s + describeArtifact(pomArtifact, skipped));

            // main artifact (when available: pom packaging does not provide main artifact)
            Optional<ProducedArtifact> mainArtifact = p.getMainArtifact();
            if (mainArtifact.isPresent()) {
                Optional<Path> mainFile = session.getArtifactPath(mainArtifact.get());
                if (mainFile.isPresent()) {
                    log.info(s + describeArtifact(OutputArtifact.of(mainArtifact.get(), mainFile.get()), skipped));
                }
            }

            // attached artifacts (when available)
            for (ProducedArtifact attachedArtifact : attachedArtifacts) {
                OutputArtifact a = OutputArtifact.of(
                        attachedArtifact,
                        session.getArtifactPath(attachedArtifact).orElse(null));
                if ("pom".equals(a.extension()) && "consumer".equals(a.classifier())) {
                    // ignore transient consumer POM attachment
                    continue;
                }
                boolean ignored = skipped ? false : isIgnore(a);
                String i = skipped ? s : (ignored ? "RB-ignored   " : "             ");
                log.info(i + describeArtifact(a, skipped || ignored));
            }
        }
    }

    private boolean isIgnore(OutputArtifact a) {
        if (a.extension().endsWith(".asc")) {
            return true;
        }
        if (bi.getIgnoreJavadoc() && "javadoc".equals(a.classifier())) {
            return true;
        }
        return bi.isIgnore(a);
    }

    private String describeArtifact(OutputArtifact a) throws MojoException {
        return describeArtifact(a, false);
    }

    private String describeArtifact(OutputArtifact a, boolean skipped) throws MojoException {
        String sha256 = skipped ? "" : (" " + sha256(a.file()));
        String ce = ("".equals(a.classifier()) ? "" : (':' + a.classifier()))
                + ("jar".equals(a.extension()) ? "" : (":" + a.extension()));
        String path = rootPath.relativize(a.file()).toString();
        String remoteFilename = a.filename();
        return /*a.getGroupId() + ':' +*/ a.artifactId() /*+ ':' + a.getVersion()*/
                + ce + " = "
                + path + " "
                + (path.endsWith(remoteFilename) ? "-" : remoteFilename)
                + " " + size(a.file()) + sha256;
    }

    private static long size(Path file) throws MojoException {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new MojoException("cannot read " + file, e);
        }
    }

    private String sha256(Path file) throws MojoException {
        if (Files.isDirectory(file)) {
            return "directory";
        }
        try (InputStream is = Files.newInputStream(file)) {
            return DigestUtils.sha256Hex(is);
        } catch (IOException ioe) {
            throw new MojoException("cannot read " + file, ioe);
        }
    }

    static Project getReactorParent(Session session, Project project) {
        Project reactorParent = project;
        while (isInReactor(session, reactorParent.getParent().orElse(null))) {
            reactorParent = reactorParent.getParent().get();
        }
        return reactorParent;
    }

    @Override
    public void execute(Map<OutputArtifact, String> artifacts) throws MojoException {
        // buildinfo generation skipped, method not called
    }
}
