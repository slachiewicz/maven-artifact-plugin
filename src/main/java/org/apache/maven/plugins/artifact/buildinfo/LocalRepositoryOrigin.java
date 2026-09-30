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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Finds where a file of the local repository comes from, with the <code>_remote.repositories</code> file that the
 * local repository keeps in each directory: a line <code>file&gt;repositoryId=</code> says that the file has been
 * downloaded from the repository, and a line <code>file&gt;=</code> that it has been installed locally.
 * <p>
 * The Maven 4 API gives the path of a resolved artifact, but not the repository that provided it.
 */
final class LocalRepositoryOrigin {

    private static final String TRACKING_FILE = "_remote.repositories";

    private LocalRepositoryOrigin() {}

    /**
     * @param file a file of the local repository
     * @param repositoryId the id of the repository the file is wanted from
     * @return {@code true} if the file has been installed locally, {@code false} if it has been downloaded from the
     *         repository or from another one
     */
    static boolean isInstalledLocally(Path file, String repositoryId) {
        Path tracking = file.resolveSibling(TRACKING_FILE);
        if (!Files.isRegularFile(tracking)) {
            // a local repository that does not track the origin of its files: nothing tells the file is downloaded
            return true;
        }
        try {
            List<String> lines = Files.readAllLines(tracking, StandardCharsets.UTF_8);
            String prefix = file.getFileName() + ">";
            boolean tracked = false;
            for (String line : lines) {
                if (line.startsWith(prefix)) {
                    tracked = true;
                    String repo = line.substring(prefix.length(), line.lastIndexOf('='));
                    if (repo.isEmpty()) {
                        return true;
                    }
                    if (repo.equals(repositoryId)) {
                        return false;
                    }
                }
            }
            return !tracked;
        } catch (IOException | IndexOutOfBoundsException e) {
            return true;
        }
    }

    /**
     * @param file a file of the local repository
     * @return {@code true} if the file has been installed locally, whatever repository it may be downloaded from
     */
    static boolean isInstalledLocally(Path file) {
        Path tracking = file.resolveSibling(TRACKING_FILE);
        if (!Files.isRegularFile(tracking)) {
            return true;
        }
        try {
            String prefix = file.getFileName() + ">";
            for (String line : Files.readAllLines(tracking, StandardCharsets.UTF_8)) {
                if (line.equals(prefix + "=")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return true;
        }
    }
}
