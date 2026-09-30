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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputArtifactTest {

    @Test
    void filenameFollowsTheRepositoryLayout() {
        assertEquals(
                "a-1.0-SNAPSHOT-sources.jar",
                new OutputArtifact("g", "a", "1.0-SNAPSHOT", "sources", "jar", null).filename());
        assertEquals("a-1.0.pom", new OutputArtifact("g", "a", "1.0", "", "pom", null).filename());
        assertEquals("a-1.0", new OutputArtifact("g", "a", "1.0", null, null, null).filename());
    }

    @Test
    void idListsTheCoordinates() {
        assertEquals("g:a:jar:1.0", new OutputArtifact("g", "a", "1.0", "", "jar", null).id());
        assertEquals("g:a:jar:tests:1.0", new OutputArtifact("g", "a", "1.0", "tests", "jar", null).id());
    }

    @Test
    void fileIsPartOfTheIdentity() {
        OutputArtifact one = new OutputArtifact("g", "a", "1.0", "", "jar", Path.of("one"));

        assertEquals(one, new OutputArtifact("g", "a", "1.0", "", "jar", Path.of("one")));
        assertEquals(one.withFile(Path.of("two")), new OutputArtifact("g", "a", "1.0", "", "jar", Path.of("two")));
    }
}
