/*
 * Copyright 2018 the original author or authors.
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

package org.gradle.performance.regression.nativeplatform

import org.gradle.initialization.ParallelismBuildOptions
import org.gradle.performance.AbstractCrossVersionPerformanceTest
import org.gradle.profiler.BuildContext
import org.gradle.profiler.mutations.AbstractFileChangeMutator
import org.gradle.util.Requires
import org.gradle.util.TestPrecondition
import spock.lang.Unroll

@Requires(TestPrecondition.LINUX)
class SwiftBuildPerformanceTest extends AbstractCrossVersionPerformanceTest {
    def setup() {
        runner.minimumBaseVersion = '4.6'
        runner.targetVersions = ["6.7-20200824220048+0000"]
        runner.args += ["--parallel", "--${ParallelismBuildOptions.MaxWorkersOption.LONG_OPTION}=6"]
    }

    @Unroll
    def "up-to-date assemble on #testProject"() {
        given:
        runner.testProject = testProject
        runner.tasksToRun = ["assemble"]
        runner.gradleOpts = ["-Xms$maxMemory", "-Xmx$maxMemory"]

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject        | maxMemory
        'mediumSwiftMulti' | '1G'
        'bigSwiftApp'      | '1G'
    }

    @Unroll
    def "incremental compile on #testProject"() {
        given:
        runner.testProject = testProject
        runner.tasksToRun = ["assemble"]
        runner.gradleOpts = ["-Xms$maxMemory", "-Xmx$maxMemory"]
        runner.addBuildMutator { invocationSettings -> new ChangeSwiftFileMutator(new File(invocationSettings.projectDir, fileToChange)) }

        when:
        def result = runner.run()

        then:
        result.assertCurrentVersionHasNotRegressed()

        where:
        testProject        | maxMemory | fileToChange
        "mediumSwiftMulti" | '1G'      | 'lib6api3/src/main/swift/Lib6Api3Impl2Api.swift'
        'bigSwiftApp'      | '1G'      | 'src/main/swift//AppImpl54Api3.swift'
    }

    private static class ChangeSwiftFileMutator extends AbstractFileChangeMutator {
        ChangeSwiftFileMutator(File sourceFile) {
            super(sourceFile)
            if (!sourceFile.absolutePath.endsWith('.swift')) {
                throw new IllegalArgumentException('Can only modify Swift source')
            }
        }

        @Override
        protected void applyChangeTo(BuildContext context, StringBuilder text) {
            def location = text.indexOf("public init() { }")
            text.insert(location, "var ${context.getUniqueBuildId()} : Int = 0\n    ")
        }
    }

}
