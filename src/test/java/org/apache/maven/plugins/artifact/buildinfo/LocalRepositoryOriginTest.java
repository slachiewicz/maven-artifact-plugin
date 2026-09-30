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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRepositoryOriginTest {

    @TempDir
    private Path repo;

    private Path artifact(String tracking) throws Exception {
        Path file = Files.createFile(repo.resolve("a-1.0.jar"));
        if (tracking != null) {
            Files.write(repo.resolve("_remote.repositories"), tracking.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    @Test
    void installedFileHasAnEmptyRepositoryId() throws Exception {
        Path file = artifact("#NOTE\na-1.0.jar>=\n");

        assertTrue(LocalRepositoryOrigin.isInstalledLocally(file));
        assertTrue(LocalRepositoryOrigin.isInstalledLocally(file, "central"));
    }

    @Test
    void downloadedFileIsNotInstalled() throws Exception {
        Path file = artifact("#NOTE\na-1.0.jar>central=\n");

        assertFalse(LocalRepositoryOrigin.isInstalledLocally(file));
        assertFalse(LocalRepositoryOrigin.isInstalledLocally(file, "central"));
    }

    @Test
    void fileDownloadedFromAnotherRepositoryIsNotTheReference() throws Exception {
        Path file = artifact("a-1.0.jar>other=\n");

        // not installed, but not available from the wanted repository either: the caller downloads it again
        assertFalse(LocalRepositoryOrigin.isInstalledLocally(file));
        assertFalse(LocalRepositoryOrigin.isInstalledLocally(file, "central"));
    }

    @Test
    void fileWithoutTrackingIsTakenAsInstalled() throws Exception {
        Path file = artifact(null);

        assertTrue(LocalRepositoryOrigin.isInstalledLocally(file));
        assertTrue(LocalRepositoryOrigin.isInstalledLocally(file, "central"));
    }

    @Test
    void trackingOfOtherFilesIsIgnored() throws Exception {
        Path file = artifact("a-1.0.pom>central=\n");

        assertTrue(LocalRepositoryOrigin.isInstalledLocally(file, "central"));
        assertFalse(LocalRepositoryOrigin.isInstalledLocally(file));
    }
}
