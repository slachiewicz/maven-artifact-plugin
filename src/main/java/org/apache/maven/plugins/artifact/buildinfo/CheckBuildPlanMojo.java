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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.api.Lifecycle;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.Version;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.model.Build;
import org.apache.maven.api.model.Plugin;
import org.apache.maven.api.model.PluginExecution;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.LifecycleRegistry;
import org.apache.maven.plugins.artifact.buildinfo.RangesUtil.RangeDependency;

/**
 * Check from buildplan that plugins used don't have known Reproducible Builds issues.
 *
 * @since 3.3.0
 */
@Mojo(name = "check-buildplan")
public class CheckBuildPlanMojo implements org.apache.maven.api.plugin.Mojo {
    @Inject
    private Project project;

    @Inject
    private Session session;

    @Inject
    private Log log;

    /** Allow to specify which goals/phases will be used to calculate execution plan. */
    @Parameter(property = "check.buildplan.tasks", defaultValue = "deploy")
    private String[] tasks;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    /**
     * Diagnose {@code outputTimestamp} effective value based on execution context.
     *
     * @since 3.5.2
     */
    @Parameter(property = "diagnose", defaultValue = "false")
    private boolean diagnose;

    /**
     * Provide a plugin issues property file to override plugin's <code>not-reproducible-plugins.properties</code>.
     */
    @Parameter(property = "check.plugin-issues")
    private Path pluginIssues;

    /**
     * Make build fail if execution plan contains non-reproducible plugins.
     */
    @Parameter(property = "check.failOnNonReproducible", defaultValue = "true")
    private boolean failOnNonReproducible;

    /**
     * Calculates the plugins of the execution plan.
     * <p>
     * The Maven 4 API has no equivalent of the execution plan that a <code>LifecycleExecutor</code> calculates for
     * tasks: the plan is taken from the effective POM, which has the plugins that the packaging binds to the
     * lifecycle along with the ones of the POM, with the executions that run in a phase of the lifecycles of the
     * tasks. An execution without phase is always in the plan, because the default phase of its goals is in the
     * plugin descriptor, and goals given as tasks are not in the plan.
     *
     * @return the plugins of the plan, each once
     */
    protected Map<String, Plugin> calculatePlugins() throws MojoException {
        LifecycleRegistry lifecycleRegistry = session.getService(LifecycleRegistry.class);

        // the phases up to the phase of each task, in each lifecycle
        Set<String> phases = new HashSet<>();
        for (String task : tasks) {
            boolean found = false;
            for (Lifecycle lifecycle : lifecycleRegistry) {
                List<String> lifecyclePhases = lifecycleRegistry.computePhases(lifecycle);
                int index = lifecyclePhases.indexOf(task);
                if (index >= 0) {
                    List<String> upToTask = lifecyclePhases.subList(0, index + 1);
                    phases.addAll(upToTask);
                    // the executions may name the phases the Maven 3 way, like process-resources
                    for (Lifecycle.Alias alias : lifecycle.aliases()) {
                        if (upToTask.contains(alias.v4Phase())) {
                            phases.add(alias.v3Phase());
                        }
                    }
                    found = true;
                }
            }
            if (!found && !task.contains(":")) {
                throw new MojoException("Cannot calculate Maven execution plan: unknown phase " + task);
            }
        }

        Map<String, Plugin> plugins = new LinkedHashMap<>();
        Build build = project.getModel().getBuild();
        if (build != null) {
            for (Plugin plugin : build.getPlugins()) {
                boolean inPlan = false;
                for (PluginExecution execution : plugin.getExecutions()) {
                    if (execution.getGoals().isEmpty()) {
                        continue;
                    }
                    if (execution.getPhase() == null || phases.contains(execution.getPhase())) {
                        inPlan = true;
                    }
                }
                if (inPlan) {
                    plugins.putIfAbsent(plugin.getId(), plugin);
                }
            }
        }
        return plugins;
    }

    @Override
    public void execute() throws MojoException {
        boolean fail = AbstractBuildinfoMojo.hasBadOutputTimestamp(outputTimestamp, log, project, session, diagnose);

        // TODO check maven-jar-plugin module-info.class?

        Properties issues = loadIssues();

        Map<String, Plugin> plan = calculatePlugins();

        int okCount = 0;
        for (Plugin plugin : plan.values()) {
            String id = plugin.getId();

            // check reproducibility status
            String issue = issues.getProperty(plugin.getKey());
            if (issue == null) {
                okCount++;
                log.debug("No known issue with " + id);
            } else if (issue.startsWith("fail:")) {
                String logMessage = "plugin without solution " + id + ", see " + issue.substring(5);
                if (failOnNonReproducible) {
                    log.error(logMessage);
                } else {
                    log.warn(logMessage);
                }
                fail = true;
            } else {
                Version minimum = session.parseVersion(issue);
                Version version = session.parseVersion(plugin.getVersion());
                if (version.compareTo(minimum) < 0) {
                    String logMessage = "plugin with non-reproducible output: " + id + ", require minimum " + issue;
                    if (failOnNonReproducible) {
                        log.error(logMessage);
                    } else {
                        log.warn(logMessage);
                    }
                    fail = true;
                } else {
                    okCount++;
                    log.debug("No known issue with " + id + " (>= " + issue + ")");
                }
            }
        }
        if (okCount > 0) {
            log.info("No known issue in " + okCount + " plugins");
        }

        fail |= checkVersionRangeInDependencies();

        if (fail) {
            log.info("current module pom.xml is " + project.getBasedir() + "/pom.xml");
            Optional<Project> parent = project.getParent();
            while (parent.isPresent() && AbstractBuildinfoMojo.isInReactor(session, parent.get())) {
                log.info("        parent pom.xml is " + parent.get().getBasedir() + "/pom.xml");
                parent = parent.get().getParent();
            }
            String message = "non-reproducible plugin or configuration found with fix available";
            if (failOnNonReproducible) {
                throw new MojoException(message);
            } else {
                log.warn(message);
            }
        }
    }

    private boolean checkVersionRangeInDependencies() throws MojoException {
        List<RangeDependency> versionRangeDependencies = RangesUtil.findVersionRangeDependencies(session, project);
        if (!versionRangeDependencies.isEmpty()) {
            String message =
                    "Version specification with a range found in dependencies may make the project build non-reproducible"
                            + versionRangeDependencies.stream()
                                    .map(RangeDependency::message)
                                    .collect(Collectors.joining(
                                            System.lineSeparator() + " - ", System.lineSeparator() + " - ", ""));
            if (failOnNonReproducible) {
                log.error(message);
            } else {
                log.warn(message);
            }
        }
        return !versionRangeDependencies.isEmpty();
    }

    private Properties loadIssues() throws MojoException {
        try (InputStream in = (pluginIssues == null)
                ? getClass().getResourceAsStream("not-reproducible-plugins.properties")
                : Files.newInputStream(pluginIssues)) {
            Properties prop = new Properties();
            prop.load(in);

            Properties result = new Properties();
            for (Map.Entry<Object, Object> entry : prop.entrySet()) {
                String plugin = entry.getKey().toString().replace('+', ':');
                if (!plugin.contains(":")) {
                    plugin = "org.apache.maven.plugins:" + plugin;
                }
                result.put(plugin, entry.getValue());
            }
            return result;
        } catch (IOException ioe) {
            throw new MojoException("Cannot load issues file", ioe);
        }
    }
}
