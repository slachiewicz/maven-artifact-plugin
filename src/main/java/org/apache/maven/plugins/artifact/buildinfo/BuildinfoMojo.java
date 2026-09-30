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

import java.util.Map;

import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;

/**
 * Creates a buildinfo file recording build environment and output (from {@code package}), as specified in
 * <a href="https://reproducible-builds.org/docs/jvm/">Reproducible Builds for the JVM</a>
 * for mono-module build, and extended for multi-module build.
 * @deprecated buildinfo is an internal format, prefer the {@code describe-build-output} or {@code compare} goals
 */
@Mojo(name = "buildinfo", defaultPhase = "verify")
@Deprecated
public class BuildinfoMojo extends AbstractBuildinfoMojo {
    /**
     * Specifies whether to attach the generated buildinfo file to the project.
     */
    @Parameter(property = "buildinfo.attach", defaultValue = "true")
    private boolean attach;

    @Override
    public void execute(Map<OutputArtifact, String> artifacts) throws MojoException {
        // attach / deploy feature has been removed, see #216
    }
}
