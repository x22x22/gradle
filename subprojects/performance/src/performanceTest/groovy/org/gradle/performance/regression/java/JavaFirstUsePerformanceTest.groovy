/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.performance.regression.java

import org.gradle.performance.AbstractCrossVersionPerformanceTest
import org.gradle.performance.categories.SlowPerformanceRegressionTest
import org.gradle.profiler.mutations.AbstractCleanupMutator
import org.gradle.profiler.mutations.ClearGradleUserHomeMutator
import org.gradle.profiler.mutations.ClearProjectCacheMutator
import org.junit.experimental.categories.Category
import spock.lang.Unroll

import static org.gradle.performance.generator.JavaTestProject.LARGE_JAVA_MULTI_PROJECT
import static org.gradle.performance.generator.JavaTestProject.LARGE_JAVA_MULTI_PROJECT_KOTLIN_DSL
import static org.gradle.performance.generator.JavaTestProject.LARGE_MONOLITHIC_JAVA_PROJECT

@Category(SlowPerformanceRegressionTest)
class JavaFirstUsePerformanceTest extends AbstractCrossVersionPerformanceTest {

    def setup() {
        runner.targetVersions = ["6.7-20200824220048+0000"]
    }

    @Unroll
    def "first use of #testProject"() {
        given:
        runner.testProject = testProject
        runner.gradleOpts = ["-Xms${testProject.daemonMemory}", "-Xmx${testProject.daemonMemory}"]
        runner.tasksToRun = ['tasks']
        runner.runs = runs
        runner.useDaemon = false
        runner.addBuildMutator { invocationSettings ->
            new ClearGradleUserHomeMutator(invocationSettings.gradleUserHome, AbstractCleanupMutator.CleanupSchedule.BUILD)
        }
        runner.addBuildMutator { invocationSettings ->
            new ClearProjectCacheMutator(invocationSettings.projectDir, AbstractCleanupMutator.CleanupSchedule.BUILD)
        }

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject                         | runs
        LARGE_MONOLITHIC_JAVA_PROJECT       | 10
        LARGE_JAVA_MULTI_PROJECT            | 10
        LARGE_JAVA_MULTI_PROJECT_KOTLIN_DSL | 5
    }

    @Unroll
    def "clean checkout of #testProject"() {
        given:
        runner.testProject = testProject
        runner.gradleOpts = ["-Xms${testProject.daemonMemory}", "-Xmx${testProject.daemonMemory}"]
        runner.tasksToRun = ['tasks']
        runner.useDaemon = false
        runner.addBuildMutator { invocationSettings ->
            new ClearProjectCacheMutator(invocationSettings.projectDir, AbstractCleanupMutator.CleanupSchedule.BUILD)
        }

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject                              | _
        LARGE_MONOLITHIC_JAVA_PROJECT            | _
        LARGE_JAVA_MULTI_PROJECT                 | _
        LARGE_JAVA_MULTI_PROJECT_KOTLIN_DSL      | _
    }

    @Unroll
    def "cold daemon on #testProject"() {
        given:
        runner.testProject = testProject
        runner.gradleOpts = ["-Xms${testProject.daemonMemory}", "-Xmx${testProject.daemonMemory}"]
        runner.tasksToRun = ['tasks']
        runner.useDaemon = false

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject                              | _
        LARGE_MONOLITHIC_JAVA_PROJECT            | _
        LARGE_JAVA_MULTI_PROJECT                 | _
        LARGE_JAVA_MULTI_PROJECT_KOTLIN_DSL      | _
    }
}
