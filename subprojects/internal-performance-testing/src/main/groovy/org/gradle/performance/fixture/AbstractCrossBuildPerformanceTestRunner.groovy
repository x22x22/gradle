/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.performance.fixture

import groovy.transform.CompileStatic
import org.gradle.integtests.fixtures.executer.GradleDistribution
import org.gradle.integtests.fixtures.executer.IntegrationTestBuildContext
import org.gradle.integtests.fixtures.executer.UnderDevelopmentGradleDistribution
import org.gradle.internal.time.Clock
import org.gradle.internal.time.Time
import org.gradle.performance.results.CrossBuildPerformanceResults
import org.gradle.performance.results.DataReporter
import org.gradle.performance.results.ResultsStore
import org.gradle.performance.results.ResultsStoreHelper
import org.gradle.profiler.BuildMutator
import org.gradle.profiler.InvocationSettings
import org.junit.Assume

import java.util.function.Function

@CompileStatic
abstract class AbstractCrossBuildPerformanceTestRunner<R extends CrossBuildPerformanceResults> {
    private final List<Function<InvocationSettings, BuildMutator>> buildMutators = []
    private final List<String> measuredBuildOperations = []

    final IntegrationTestBuildContext buildContext
    final GradleDistribution gradleDistribution
    final BuildExperimentRunner experimentRunner
    final Clock clock = Time.clock()

    String testClassName
    String testId
    String testGroup
    List<BuildExperimentSpec> specs = []

    final DataReporter<R> reporter
    final ResultsStore resultsStore

    AbstractCrossBuildPerformanceTestRunner(BuildExperimentRunner experimentRunner, ResultsStore resultsStore, DataReporter<R> dataReporter, IntegrationTestBuildContext buildContext) {
        this.reporter = dataReporter
        this.resultsStore = resultsStore
        this.experimentRunner = experimentRunner
        this.buildContext = buildContext
        this.gradleDistribution = new UnderDevelopmentGradleDistribution(buildContext)
    }

    void baseline(@DelegatesTo(GradleBuildExperimentSpec.GradleBuilder) Closure<?> configureAction) {
        buildSpec(configureAction)
    }

    void buildSpec(@DelegatesTo(GradleBuildExperimentSpec.GradleBuilder) Closure<?> configureAction) {
        def builder = GradleBuildExperimentSpec.builder()
        configureGradleSpec(builder)
        configureAndAddSpec(builder, configureAction)
    }

    void addBuildMutator(Function<InvocationSettings, BuildMutator> buildMutator) {
        buildMutators.add(buildMutator)
    }

    protected void configureGradleSpec(GradleBuildExperimentSpec.GradleBuilder builder) {
        builder.measuredBuildOperations.addAll(measuredBuildOperations)
        builder.invocation.distribution(gradleDistribution)
    }

    protected void configureAndAddSpec(BuildExperimentSpec.Builder builder, Closure<?> configureAction) {
        defaultSpec(builder)
        builder.with(configureAction as Closure<Object>)
        finalizeSpec(builder)
        def specification = builder.build()

        if (specs.any { it.displayName == specification.displayName }) {
            throw new IllegalStateException("Multiple specifications with display name '${specification.displayName}.")
        }
        specs << specification
    }

    protected void defaultSpec(BuildExperimentSpec.Builder builder) {
        builder.buildMutators.addAll(buildMutators)
    }

    protected void finalizeSpec(BuildExperimentSpec.Builder builder) {
        assert builder.projectName
        assert builder.workingDirectory
        builder.invocation.workingDirectory = new File(builder.workingDirectory, builder.displayName)
        if (builder instanceof GradleBuildExperimentSpec.GradleBuilder) {
            finalizeGradleSpec(builder)
        }
    }

    protected void finalizeGradleSpec(GradleBuildExperimentSpec.GradleBuilder builder) {
        def invocation = builder.invocation
        invocation.gradleOptions = customizeJvmOptions(invocation.gradleOptions)
    }

    protected static List<String> customizeJvmOptions(List<String> jvmOptions) {
        PerformanceTestJvmOptions.normalizeJvmOptions(jvmOptions)
    }

    abstract R newResult()

    R run() {
        assert !specs.empty
        assert testId

        Assume.assumeTrue(TestScenarioSelector.shouldRun(testClassName, testId, specs.projectName.toSet(), resultsStore))

        def results = newResult()

        runAllSpecifications(results)

        results.endTime = clock.getCurrentTime()

        reporter.report(results)

        return results
    }

    void runAllSpecifications(R results) {
        specs.each {
            def operations = results.buildResult(it.displayInfo)
            experimentRunner.run(it, operations)
        }
    }

    protected static String determineChannel() {
        ResultsStoreHelper.determineChannel()
    }

    protected static String determineTeamCityBuildId() {
        ResultsStoreHelper.determineTeamCityBuildId()
    }
}
