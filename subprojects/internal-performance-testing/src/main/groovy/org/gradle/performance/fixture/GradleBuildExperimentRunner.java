/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.performance.fixture;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import groovy.transform.CompileStatic;
import org.gradle.integtests.fixtures.executer.GradleDistribution;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.jvm.Jvm;
import org.gradle.performance.results.MeasuredOperationList;
import org.gradle.profiler.BenchmarkResultCollector;
import org.gradle.profiler.BuildAction;
import org.gradle.profiler.BuildMutatorFactory;
import org.gradle.profiler.DaemonControl;
import org.gradle.profiler.GradleBuildConfiguration;
import org.gradle.profiler.GradleBuildInvocationResult;
import org.gradle.profiler.GradleBuildInvoker;
import org.gradle.profiler.GradleScenarioDefinition;
import org.gradle.profiler.GradleScenarioInvoker;
import org.gradle.profiler.InvocationSettings;
import org.gradle.profiler.Logging;
import org.gradle.profiler.RunTasksAction;
import org.gradle.profiler.instrument.PidInstrumentation;
import org.gradle.profiler.report.CsvGenerator;
import org.gradle.profiler.result.Sample;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * {@inheritDoc}
 *
 * This runner uses Gradle profiler to execute the actual experiment.
 */
@CompileStatic
public class GradleBuildExperimentRunner extends AbstractBuildExperimentRunner {
    private static final String GRADLE_USER_HOME_NAME = "gradleUserHome";
    private PidInstrumentation pidInstrumentation;

    public GradleBuildExperimentRunner(BenchmarkResultCollector resultCollector) {
        super(resultCollector);
        try {
            this.pidInstrumentation = new PidInstrumentation();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public PidInstrumentation getPidInstrumentation() {
        return pidInstrumentation;
    }

    public void setPidInstrumentation(PidInstrumentation pidInstrumentation) {
        this.pidInstrumentation = pidInstrumentation;
    }

    @Override
    public void doRun(BuildExperimentSpec experiment, MeasuredOperationList results) {
        InvocationSpec invocationSpec = experiment.getInvocation();
        File workingDirectory = invocationSpec.getWorkingDirectory();

        if (!(invocationSpec instanceof GradleInvocationSpec)) {
            throw new IllegalArgumentException("Can only run Gradle invocations with Gradle profiler");
        }

        GradleInvocationSpec invocation = (GradleInvocationSpec) invocationSpec;
        List<String> additionalJvmOpts = new ArrayList<>();
        List<String> additionalArgs = new ArrayList<>();
        additionalArgs.add("-PbuildExperimentDisplayName=" + experiment.getDisplayName());

        GradleInvocationSpec buildSpec = invocation.withAdditionalJvmOpts(additionalJvmOpts).withAdditionalArgs(additionalArgs);

        GradleBuildExperimentSpec gradleExperiment = (GradleBuildExperimentSpec) experiment;
        InvocationSettings invocationSettings = createInvocationSettings(buildSpec, gradleExperiment);
        GradleScenarioDefinition scenarioDefinition = createScenarioDefinition(gradleExperiment, invocationSettings, invocation);
        List<Sample<GradleBuildInvocationResult>> buildOperationSamples = scenarioDefinition.getMeasuredBuildOperations().stream()
            .map(GradleBuildInvocationResult::sampleBuildOperation)
            .collect(Collectors.toList());
        Consumer<GradleBuildInvocationResult> scenarioReporter = getResultCollector().scenario(
            scenarioDefinition,
            ImmutableList.<Sample<? super GradleBuildInvocationResult>>builder()
                .add(GradleBuildInvocationResult.EXECUTION_TIME)
                .addAll(buildOperationSamples)
                .build()
        );

        try {
            GradleScenarioInvoker scenarioInvoker = createScenarioInvoker(new File(buildSpec.getWorkingDirectory(), GRADLE_USER_HOME_NAME));
            AtomicInteger iterationCount = new AtomicInteger(0);
            Logging.setupLogging(workingDirectory);
            scenarioInvoker.doRun(scenarioDefinition,
                invocationSettings,
                consumerFor(scenarioDefinition, iterationCount, results, scenarioReporter));
            getFlameGraphGenerator().generateGraphs(experiment);
            getFlameGraphGenerator().generateDifferentialGraphs();
        } catch (IOException | InterruptedException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        } finally {
            try {
                Logging.resetLogging();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private GradleScenarioInvoker createScenarioInvoker(File gradleUserHome) {
        DaemonControl daemonControl = new DaemonControl(gradleUserHome);
        return new GradleScenarioInvoker(daemonControl, pidInstrumentation);
    }

    private InvocationSettings createInvocationSettings(GradleInvocationSpec invocationSpec, GradleBuildExperimentSpec experiment) {
        File outputDir = getFlameGraphGenerator().getJfrOutputDirectory(experiment);
        GradleBuildInvoker daemonInvoker = invocationSpec.getUseToolingApi() ? GradleBuildInvoker.ToolingApi : GradleBuildInvoker.Cli;
        GradleBuildInvoker invoker = invocationSpec.isUseDaemon()
            ? daemonInvoker
            : (daemonInvoker == GradleBuildInvoker.ToolingApi
            ? daemonInvoker.withColdDaemon()
            : GradleBuildInvoker.CliNoDaemon);
        return new InvocationSettings(
            invocationSpec.getWorkingDirectory(),
            getProfiler(),
            true,
            outputDir,
            invoker,
            false,
            null,
            ImmutableList.of(invocationSpec.getGradleDistribution().getVersion().getVersion()),
            invocationSpec.getTasksToRun(),
            ImmutableMap.of(),
            new File(invocationSpec.getWorkingDirectory(), GRADLE_USER_HOME_NAME),
            warmupsForExperiment(experiment),
            invocationsForExperiment(experiment),
            false,
            experiment.getMeasuredBuildOperations(),
            CsvGenerator.Format.LONG
        );
    }

    private GradleScenarioDefinition createScenarioDefinition(GradleBuildExperimentSpec experimentSpec, InvocationSettings invocationSettings, GradleInvocationSpec invocationSpec) {
        GradleDistribution gradleDistribution = invocationSpec.getGradleDistribution();
        List<String> cleanTasks = invocationSpec.getCleanTasks();
        return new GradleScenarioDefinition(
            experimentSpec.getDisplayName(),
            experimentSpec.getDisplayName(),
            (GradleBuildInvoker) invocationSettings.getInvoker(),
            new GradleBuildConfiguration(gradleDistribution.getVersion(), gradleDistribution.getGradleHomeDir(), Jvm.current().getJavaHome(), invocationSpec.getJvmOpts(), false),
            new RunTasksAction(invocationSettings.getTargets()),
            cleanTasks.isEmpty()
                ? BuildAction.NO_OP
                : new RunTasksAction(cleanTasks),
            invocationSpec.getArgs(),
            invocationSettings.getSystemProperties(),
            new BuildMutatorFactory(experimentSpec.getBuildMutators().stream()
                .map(mutatorFunction -> toMutatorSupplierForSettings(invocationSettings, mutatorFunction))
                .collect(Collectors.toList())
            ),
            invocationSettings.getWarmUpCount(),
            invocationSettings.getBuildCount(),
            invocationSettings.getOutputDir(),
            ImmutableList.of(),
            invocationSettings.getMeasuredBuildOperations()
        );
    }


}
