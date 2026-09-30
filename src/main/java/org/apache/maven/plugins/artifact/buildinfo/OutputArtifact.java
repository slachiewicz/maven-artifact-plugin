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

import org.apache.maven.api.Artifact;

/**
 * An artifact produced by the build, with the file that holds it: the Maven 4 API artifacts do not know their
 * file, which is kept by the session.
 *
 * @param groupId the groupId
 * @param artifactId the artifactId
 * @param version the base version
 * @param classifier the classifier, empty if none
 * @param extension the extension, empty if none
 * @param file the file, {@code null} if the artifact has none
 */
record OutputArtifact(
        String groupId, String artifactId, String version, String classifier, String extension, Path file) {

    OutputArtifact {
        classifier = classifier == null ? "" : classifier;
        extension = extension == null ? "" : extension;
    }

    static OutputArtifact of(Artifact artifact, Path file) {
        return new OutputArtifact(
                artifact.getGroupId(),
                artifact.getArtifactId(),
                artifact.getBaseVersion().toString(),
                artifact.getClassifier(),
                artifact.getExtension(),
                file);
    }

    OutputArtifact withFile(Path newFile) {
        return new OutputArtifact(groupId, artifactId, version, classifier, extension, newFile);
    }

    /**
     * @return the coordinates <code>groupId:artifactId:extension[:classifier]:version</code>
     */
    String id() {
        return groupId
                + ':'
                + artifactId
                + ':'
                + extension
                + (classifier.isEmpty() ? "" : ':' + classifier)
                + ':'
                + version;
    }

    /**
     * @return the file name in a repository
     */
    String filename() {
        StringBuilder path = new StringBuilder(128);

        path.append(artifactId).append('-').append(version);

        if (!classifier.isEmpty()) {
            path.append('-').append(classifier);
        }

        if (!extension.isEmpty()) {
            path.append('.').append(extension);
        }

        return path.toString();
    }

    @Override
    public String toString() {
        return id();
    }
}
