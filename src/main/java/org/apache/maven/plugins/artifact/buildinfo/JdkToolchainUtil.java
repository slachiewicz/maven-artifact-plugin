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
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.apache.maven.api.Toolchain;

/**
 * A helper to get JDK version from a JDK toolchain.
 */
class JdkToolchainUtil {
    static String getJavaVersion(Toolchain toolchain) {
        String version = "unknown";
        String java = toolchain.findTool("java");
        if (java != null) {
            try {
                Process process = new ProcessBuilder(java, "-version")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                // "java -version" prints to the error stream
                List<String> lines = new ArrayList<>();
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getErrorStream(), Charset.defaultCharset()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                }
                process.waitFor(1, TimeUnit.MINUTES);
                version = String.join(":", lines);
                if (version.trim().isEmpty()) {
                    version = "unable to detect...";
                }
            } catch (IOException e) {
                version = e.toString();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                version = e.toString();
            }
        }
        return version;
    }
}
