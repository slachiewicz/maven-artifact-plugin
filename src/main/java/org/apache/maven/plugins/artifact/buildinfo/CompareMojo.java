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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.maven.api.Project;
import org.apache.maven.api.RemoteRepository;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;

/**
 * Compare current build output (from {@code package}) against reference either previously {@code install}-ed or downloaded from a remote
 * repository: comparison results go to {@code .buildcompare} file.
 *
 * @since 3.2.0
 */
@Mojo(name = "compare")
public class CompareMojo extends AbstractBuildinfoMojo {
    /**
     * Repository for reference build, containing either reference buildinfo file or reference artifacts.<br/>
     * Format: <code>id</code> or <code>url</code> or <code>id::url</code>
     * <dl>
     * <dt>id</dt>
     * <dd>The repository id</dd>
     * <dt>url</dt>
     * <dd>The url of the repository</dd>
     * </dl>
     * @see <a href="https://maven.apache.org/ref/current/maven-model/maven.html#repository">repository definition</a>
     */
    @Parameter(property = "reference.repo", defaultValue = "central")
    private String referenceRepo;

    /**
     * Compare aggregate only (ie wait for the last module) or also compare on each module.
     * @since 3.2.0
     */
    @Parameter(property = "compare.aggregate.only", defaultValue = "false")
    private boolean aggregateOnly;

    /**
     * Fail the build if differences are found against reference build.
     * @since 3.5.0
     */
    @Parameter(property = "compare.fail", defaultValue = "true")
    private boolean fail;

    @Override
    public void execute(Map<OutputArtifact, String> artifacts) throws MojoException {
        log.info("Checking against reference build from local repository or " + referenceRepo + "...");
        checkAgainstReference(artifacts, session.getProjects().size() == 1);
    }

    @Override
    protected void skip(Project last) throws MojoException {
        if (aggregateOnly) {
            return;
        }

        // try to download reference artifacts for current project and check if there are issues to give early feedback
        checkAgainstReference(generateBuildinfo(true), true);
    }

    /**
     * Check current build result with reference.
     *
     * @param artifacts a Map of artifacts added to the build info with their associated property key prefix
     *            (<code>outputs.[#module.].#artifact</code>)
     * @throws MojoException if anything goes wrong
     */
    private void checkAgainstReference(Map<OutputArtifact, String> artifacts, boolean mono) throws MojoException {
        Project root = mono ? project : getTopLevelProject(session);
        Path referenceDir = Paths.get(root.getBuild().getDirectory()).resolve("reference");
        try {
            Files.createDirectories(referenceDir);
        } catch (IOException e) {
            throw new MojoException("Error creating directory " + referenceDir, e);
        }

        // create reference buildinfo
        Path referenceBuildinfo = createReferenceBuildinfo(mono, artifacts, referenceDir);

        // compare outputs from reference buildinfo vs actual
        compareWithReference(artifacts, referenceBuildinfo);
    }

    private Path createReferenceBuildinfo(boolean mono, Map<OutputArtifact, String> artifacts, Path referenceDir)
            throws MojoException {
        RemoteRepository repo = createReferenceRepo();

        ReferenceBuildinfoUtil rmb = new ReferenceBuildinfoUtil(log, referenceDir, artifacts, session);

        return rmb.createReferenceBuildinfo(repo, buildinfoFile, mono, remoteRepos());
    }

    /**
     * @return the remote repositories of the project
     */
    private List<RemoteRepository> remoteRepos() {
        return session.getService(ProjectManager.class).getRemoteProjectRepositories(project);
    }

    private void compareWithReference(Map<OutputArtifact, String> artifacts, Path referenceBuildinfo)
            throws MojoException {
        Properties actual = BuildInfoWriter.loadOutputProperties(buildinfoFile);
        Properties reference = BuildInfoWriter.loadOutputProperties(referenceBuildinfo);

        int ok = 0;
        List<String> okFilenames = new ArrayList<>();
        List<String> koFilenames = new ArrayList<>();
        List<String> missingFilenames = new ArrayList<>();
        List<String> diffoscopes = new ArrayList<>();
        List<String> ignored = new ArrayList<>();
        Path referenceDir = referenceBuildinfo.getParent();
        for (Map.Entry<OutputArtifact, String> entry : artifacts.entrySet()) {
            OutputArtifact artifact = entry.getKey();
            String prefix = entry.getValue();
            if (prefix == null) {
                // ignored file
                ignored.add(artifact.filename());
                continue;
            }

            String[] checkResult = checkArtifact(artifact, prefix, reference, actual, referenceDir);
            String filename = checkResult[0];
            String diffoscope = checkResult[1]; // diffoscope or wget

            if (diffoscope == null) {
                ok++;
                okFilenames.add(filename);
            } else {
                (diffoscope.startsWith("wget") ? missingFilenames : koFilenames).add(filename);
                diffoscopes.add(diffoscope);
            }
        }

        int ko = artifacts.size() - ok - ignored.size();
        int missing = missingFilenames.size();

        if (ko + missing > 0) {
            log.error("[Reproducible Builds] rebuild comparison result: " + ok + " files match, " + ko + " differ"
                    + ((missing == 0) ? "" : (", " + missing + " missing"))
                    + ((ignored.isEmpty()) ? "" : (", " + ignored.size() + " ignored")));
        } else {
            log.info("[Reproducible Builds] rebuild comparison result: " + ok + " files match"
                    + ((ignored.isEmpty()) ? "" : (", " + ignored.size() + " ignored")));
        }

        // save .compare file
        Path buildcompare = buildinfoFile.resolveSibling(
                buildinfoFile.getFileName().toString().replaceFirst(".buildinfo$", ".buildcompare"));
        try (PrintWriter p = new PrintWriter(new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(buildcompare), StandardCharsets.UTF_8)))) {
            p.println("version=" + project.getVersion());
            p.println("ok=" + ok);
            p.println("ko=" + ko);
            p.println("ignored=" + ignored.size());
            p.println("okFiles=\"" + String.join(" ", okFilenames) + '"');
            p.println("koFiles=\"" + String.join(" ", koFilenames) + '"');
            p.println("ignoredFiles=\"" + String.join(" ", ignored) + '"');
            if (missing > 0) {
                p.println("missing=" + missing);
                p.println("missingFiles=\"" + String.join(" ", missingFilenames) + '"');
            }
            Properties ref = new Properties();
            try (InputStream in = Files.newInputStream(referenceBuildinfo)) {
                ref.load(in);
            } catch (IOException e) {
                // nothing
            }
            String v = ref.getProperty("java.version");
            if (v != null) {
                p.println("reference_java_version=\"" + v + '"');
            }
            v = ref.getProperty("os.name");
            if (v != null) {
                p.println("reference_os_name=\"" + v + '"');
            }
            for (String diffoscope : diffoscopes) {
                p.print("# ");
                p.println(diffoscope);
            }
        } catch (IOException e) {
            throw new MojoException("Error creating file " + buildcompare, e);
        }

        String saved = "                                                 saved to " + relative(buildcompare);
        if (ko + missing > 0) {
            log.error(saved);
        } else {
            log.info(saved);
        }
        if (session.getProjects().size() > 1) {
            Project last = getLastProject();
            if (isSame(project, last)) {
                buildcompare = copyAggregateToRoot(buildcompare);
            }
        }

        if (ko + missing > 0) {
            log.error("[Reproducible Builds] to analyze the differences, see diffoscope instructions in "
                    + relative(buildcompare));
            log.error(
                    "                      see also https://maven.apache.org/guides/mini/guide-reproducible-builds.html");

            if (fail) {
                throw new MojoException("Rebuilt artifacts are different from reference");
            }
        }
    }

    // { filename, diffoscope or wget }
    private String[] checkArtifact(
            OutputArtifact artifact, String prefix, Properties reference, Properties actual, Path referenceDir)
            throws MojoException {
        String actualFilename = (String) actual.remove(prefix + ".filename");
        String actualLength = (String) actual.remove(prefix + ".length");
        String actualSha512 = (String) actual.remove(prefix + ".checksums.sha512");

        String referencePrefix = findPrefix(reference, artifact.groupId(), actualFilename);
        String referenceLength = (String) reference.remove(referencePrefix + ".length");
        String referenceSha512 = (String) reference.remove(referencePrefix + ".checksums.sha512");
        reference.remove(referencePrefix + ".groupId");

        String issue = null;
        if (referenceLength == null) {
            issue = "missing reference file";
        } else if (!actualLength.equals(referenceLength)) {
            issue = "size";
        } else if (!actualSha512.equals(referenceSha512)) {
            issue = "sha512";
        }

        if (issue != null) {
            String diffoscope = diffoscope(artifact, referenceDir);
            log.error(issue + " mismatch " + actualFilename + ": investigate with " + diffoscope);
            return new String[] {actualFilename, diffoscope};
        }
        return new String[] {actualFilename, null};
    }

    private String diffoscope(OutputArtifact a, Path referenceDir) throws MojoException {
        Path actual = a.file();
        // notice: actual file name may have been defined in pom
        // reference file name is taken from repository format
        Path reference = referenceDir.resolve(a.groupId()).resolve(getRepositoryFilename(a));
        if (actual == null) {
            return "missing file for " + a.id() + " reference = " + relative(reference) + " actual = null";
        }
        if (!Files.exists(reference)) {
            RemoteRepository repo = createReferenceRepo();
            String baseUrl = repo.getUrl();
            String url = baseUrl
                    + (baseUrl.endsWith("/") ? "" : "/")
                    + session.getPathForRemoteArtifact(repo, toApiArtifact(a))
                            .toString()
                            .replace('\\', '/');
            return wgetHint(url, relative(actual));
        }
        return "diffoscope " + relative(reference) + " " + relative(actual);
    }

    private org.apache.maven.api.Artifact toApiArtifact(OutputArtifact a) {
        return session.createArtifact(a.groupId(), a.artifactId(), a.version(), a.classifier(), a.extension(), null);
    }

    static String wgetHint(String url, String actual) {
        return "wget -- " + shellQuote(url) + "; ls -l -- " + shellQuote(actual);
    }

    private static String shellQuote(String value) {
        return '\'' + value.replace("'", "'\"'\"'") + '\'';
    }

    private String getRepositoryFilename(OutputArtifact a) {
        return session.getPathForLocalArtifact(toApiArtifact(a)).getFileName().toString();
    }

    private static String findPrefix(Properties reference, String actualGroupId, String actualFilename) {
        for (String name : reference.stringPropertyNames()) {
            if (name.endsWith(".filename") && actualFilename.equals(reference.getProperty(name))) {
                String prefix = name.substring(0, name.length() - ".filename".length());
                if (actualGroupId.equals(reference.getProperty(prefix + ".groupId"))) {
                    reference.remove(name);
                    return prefix;
                }
            }
        }
        return null;
    }

    private RemoteRepository createReferenceRepo() throws MojoException {
        if (referenceRepo.contains("::")) {
            // id::url
            int index = referenceRepo.indexOf("::");
            String id = referenceRepo.substring(0, index);
            String url = referenceRepo.substring(index + 2);
            return session.createRemoteRepository(id, url);
        } else if (referenceRepo.contains(":")) {
            // url, will use default "reference" id
            return session.createRemoteRepository("reference", referenceRepo);
        }

        // id
        for (RemoteRepository repo : remoteRepos()) {
            if (referenceRepo.equals(repo.getId())) {
                return repo;
            }
        }
        throw new MojoException("Could not find repository with id = " + referenceRepo);
    }
}
