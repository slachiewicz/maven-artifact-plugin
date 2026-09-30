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
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;

import org.apache.maven.api.Artifact;
import org.apache.maven.api.Node;
import org.apache.maven.api.PathScope;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.Session;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.services.Transport;
import org.apache.maven.api.services.TransportProvider;

/**
 * Utility to download reference artifacts and download or generate reference buildinfo.
 */
class ReferenceBuildinfoUtil {
    private static final Set<String> JAR_EXTENSIONS;

    static {
        Set<String> extensions = new HashSet<>();
        extensions.add("jar");
        extensions.add("war");
        extensions.add("ear");
        extensions.add("rar");
        JAR_EXTENSIONS = Collections.unmodifiableSet(extensions);
    }

    private final Log log;

    /**
     * Directory of the downloaded reference files.
     */
    private final Path referenceDir;

    private final Map<OutputArtifact, String> artifacts;

    private final Session session;

    ReferenceBuildinfoUtil(Log log, Path referenceDir, Map<OutputArtifact, String> artifacts, Session session) {
        this.log = log;
        this.referenceDir = referenceDir;
        this.artifacts = artifacts;
        this.session = session;
    }

    /**
     * A reference file.
     *
     * @param file the local copy
     * @param installed whether it comes from a previous installation in the local repository, and not from the
     *            reference repository
     */
    private record Reference(Path file, boolean installed) {}

    Path createReferenceBuildinfo(
            RemoteRepository repo, Path buildinfoFile, boolean mono, List<RemoteRepository> remoteRepos)
            throws MojoException {
        // download reference artifacts and guess Java version and OS
        String javaVersion = null;
        String osName = null;
        String currentJavaVersion = null;
        String currentOsName = null;
        Map<OutputArtifact, Path> referenceArtifacts = new HashMap<>();
        int localInstallArtifacts = 0;
        for (OutputArtifact artifact : artifacts.keySet()) {
            // download
            Reference referenceResult = downloadReference(repo, artifact);
            if (referenceResult == null) {
                log.warn("Reference artifact not found " + artifact);
                continue;
            }
            Path file = referenceResult.file();
            referenceArtifacts.put(artifact, file);

            if (referenceResult.installed()) {
                localInstallArtifacts++;
            }

            // guess Java version and OS
            if ((javaVersion == null) && JAR_EXTENSIONS.contains(artifact.extension())) {
                ReproducibleEnv env = extractEnv(file, artifact);
                if ((env != null) && (env.javaVersion != null)) {
                    javaVersion = env.javaVersion;
                    osName = env.osName;

                    ReproducibleEnv currentEnv = extractEnv(artifact.file(), artifact);
                    currentJavaVersion = currentEnv.javaVersion;
                    currentOsName = currentEnv.osName;
                }
            }
        }

        if (localInstallArtifacts == 0) {
            log.info("Comparing against " + artifacts.size() + " reference files from " + repo.getId() + " ("
                    + repo.getUrl() + ")");
        } else if (localInstallArtifacts == artifacts.size()) {
            log.info("Comparing against " + artifacts.size() + " reference files from previous 'mvn install'");
        } else {
            log.warn("Comparing against " + localInstallArtifacts
                    + " reference files mixed from previous 'mvn install' and "
                    + (artifacts.size() - localInstallArtifacts) + " from " + repo.getId() + " (" + repo.getUrl()
                    + ")");
        }

        Path referenceBuildinfo = null;
        try {
            // generate buildinfo from reference artifacts
            referenceBuildinfo = referenceDir.resolve(buildinfoFile.getFileName());
            try (PrintWriter p = new PrintWriter(new BufferedWriter(
                    new OutputStreamWriter(Files.newOutputStream(referenceBuildinfo), StandardCharsets.UTF_8)))) {
                BuildInfoWriter bi = new BuildInfoWriter(log, p, mono, session);

                if (javaVersion != null || osName != null) {
                    p.println("# effective build environment information");
                    if (javaVersion != null) {
                        p.println("java.version=" + javaVersion);
                        log.info("    reference build java.version: " + javaVersion);
                        if (!javaVersion.equals(currentJavaVersion)) {
                            log.error("Current build java.version: " + currentJavaVersion);
                        }
                    }
                    if (osName != null) {
                        p.println("os.name=" + osName);
                        log.info("    reference build os.name: " + osName);

                        // check against current line separator
                        if (!osName.equals(currentOsName)) {
                            log.error("Current build os.name: " + currentOsName);
                        }
                        String expectedLs = osName.startsWith("Windows") ? "\r\n" : "\n";
                        if (!expectedLs.equals(System.lineSeparator())) {
                            log.warn("Current System.lineSeparator() does not match reference build OS");

                            String ls = System.getProperty("line.separator");
                            if (!ls.equals(System.lineSeparator())) {
                                log.warn("System.lineSeparator() != System.getProperty( \"line.separator\" ): "
                                        + "too late standard system property update...");
                            }
                        }
                    }
                }

                for (Map.Entry<OutputArtifact, String> entry : artifacts.entrySet()) {
                    OutputArtifact artifact = entry.getKey();

                    if ("pom".equals(artifact.extension()) && "".equals(artifact.classifier())) {
                        checkArtifactDependenciesForLocalResolution(remoteRepos, artifact);
                    }

                    Path referenceFile = referenceArtifacts.get(artifact);
                    if (referenceFile != null) {
                        String prefix = entry.getValue();
                        bi.printFile(prefix, artifact.groupId(), referenceFile);
                    }
                }

                if (p.checkError()) {
                    throw new MojoException("Write error to " + referenceBuildinfo);
                }

                log.debug("Minimal buildinfo generated from downloaded artifacts: " + referenceBuildinfo);
            }
        } catch (IOException e) {
            throw new MojoException("Error creating file " + referenceBuildinfo, e);
        }

        return referenceBuildinfo;
    }

    private Artifact toApiArtifact(OutputArtifact artifact) {
        return session.createArtifact(
                artifact.groupId(),
                artifact.artifactId(),
                artifact.version(),
                artifact.classifier(),
                artifact.extension(),
                null);
    }

    public void checkArtifactDependenciesForLocalResolution(
            List<RemoteRepository> remoteRepos, OutputArtifact artifact) {

        try {
            // the POM is the root dependency: its dependencies are the children of the root node
            Node root = session.collectDependencies(
                    session.createDependencyCoordinates(session.createArtifactCoordinates(
                            artifact.groupId(), artifact.artifactId(), artifact.version(), artifact.extension())),
                    PathScope.MAIN_RUNTIME);

            for (Node child : root.getChildren()) {
                checkDependencyNodeTreeForLocalResolution(child, remoteRepos);
            }

        } catch (RuntimeException e) {
            log.warn("Checking for potential local artifact resolution not possible " + e);
        }
    }

    private void checkDependencyNodeTreeForLocalResolution(Node child, List<RemoteRepository> remoteRepos) {
        // check for every dependency in the dependency tree
        if (child.getChildren().isEmpty()) {
            checkArtifactForLocalResolution(child.getArtifact(), remoteRepos);
        } else {
            for (Node node : child.getChildren()) {
                checkDependencyNodeTreeForLocalResolution(node, remoteRepos);
            }
        }
    }

    /* An artifact stemming from a local repo is most likely an issue during release builds. See #146. */
    private void checkArtifactForLocalResolution(Artifact artifact, List<RemoteRepository> remoteRepos) {
        Path resolved = session.resolveArtifact(artifact, remoteRepos).getPath();

        if (resolved.startsWith(session.getLocalRepository().getPath())
                && LocalRepositoryOrigin.isInstalledLocally(resolved)) {
            log.warn(String.format(
                    "The dependency %s:%s:%s is stemming from a local install to your local Maven repository. "
                            + "Please ensure that this is intended. "
                            + "If not, consider removing this artifact and rebuilding, "
                            + "or that your locally installed artifact from %s matches public reference from remote.",
                    artifact.getGroupId(),
                    artifact.getArtifactId(),
                    artifact.getVersion(),
                    // if the artifact was resolved successfully, there is a file we can access
                    resolved.toAbsolutePath()));
        }
    }

    private ReproducibleEnv extractEnv(Path file, OutputArtifact artifact) {
        log.debug("Guessing java.version and os.name from jar " + file);
        try (JarFile jar = new JarFile(file.toFile())) {
            Manifest manifest = jar.getManifest();
            if (manifest != null) {
                String javaVersion = extractJavaVersion(manifest);
                String osName = extractOsName(artifact, jar);
                return new ReproducibleEnv(javaVersion, osName);
            } else {
                log.warn("no MANIFEST.MF found in jar " + file);
            }
        } catch (ZipException e) {
            log.warn("Corrupt jar file " + file + "\n" + e.getMessage());
        } catch (IOException e) {
            log.warn("unable to read jar file " + file + "\n due to "
                    + e.getClass().getName() + "\n" + e.getMessage());
        }
        return null;
    }

    private String extractJavaVersion(Manifest manifest) {
        Attributes attr = manifest.getMainAttributes();

        String value = attr.getValue("Build-Jdk-Spec"); // MSHARED-797
        if (value != null) {
            return value + " (from MANIFEST.MF Build-Jdk-Spec)";
        }

        value = attr.getValue("Build-Jdk");
        if (value != null) {
            return String.valueOf(value) + " (from MANIFEST.MF Build-Jdk)";
        }

        return null;
    }

    private String extractOsName(OutputArtifact a, JarFile jar) {
        String entryName = "META-INF/maven/" + a.groupId() + '/' + a.artifactId() + "/pom.properties";
        ZipEntry zipEntry = jar.getEntry(entryName);
        if (zipEntry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(zipEntry)) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            log.debug("Manifest content: " + content);
            if (content.contains("\r\n")) {
                return "Windows (from pom.properties newline)";
            } else if (content.contains("\n")) {
                return "Unix (from pom.properties newline)";
            }
        } catch (IOException e) {
            log.warn("Unable to read " + entryName + " from " + jar, e);
        }
        return null;
    }

    /**
     * Gets the reference of an artifact: the file of a previous installation in the local repository, else the one of
     * the reference repository, downloaded without looking at the files of the current build.
     *
     * @return the reference, or {@code null} if the reference repository does not have the artifact
     */
    private Reference downloadReference(RemoteRepository repo, OutputArtifact artifact) throws MojoException {
        try {
            Artifact apiArtifact = toApiArtifact(artifact);
            Path destFile = getReference(artifact.groupId(), artifact.filename());

            Path local = session.getPathForLocalArtifact(apiArtifact);
            if (Files.isRegularFile(local)) {
                boolean installed = LocalRepositoryOrigin.isInstalledLocally(local, repo.getId());
                Files.copy(local, destFile, LinkOption.NOFOLLOW_LINKS, StandardCopyOption.REPLACE_EXISTING);
                return new Reference(destFile, installed);
            }

            Path path = session.getPathForRemoteArtifact(repo, apiArtifact);
            URI uri = URI.create(path.toString().replace('\\', '/'));
            try (Transport transport =
                    session.getService(TransportProvider.class).transport(session, repo)) {
                if (!transport.get(uri, destFile)) {
                    return null;
                }
            }
            return new Reference(destFile, false);
        } catch (IOException | RuntimeException e) {
            throw new MojoException("Error resolving reference artifact " + artifact, e);
        }
    }

    private Path getReference(String groupId, String filename) throws IOException {
        Path dir = referenceDir.resolve(groupId);
        Files.createDirectories(dir);
        return dir.resolve(filename);
    }

    private static class ReproducibleEnv {
        @SuppressWarnings("checkstyle:visibilitymodifier")
        public final String javaVersion;

        @SuppressWarnings("checkstyle:visibilitymodifier")
        public final String osName;

        ReproducibleEnv(String javaVersion, String osName) {
            this.javaVersion = javaVersion;
            this.osName = osName;
        }
    }
}
