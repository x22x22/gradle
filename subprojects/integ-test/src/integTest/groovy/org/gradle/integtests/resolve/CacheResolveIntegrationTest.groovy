/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.integtests.resolve;


public class CacheResolveIntegrationTest extends AbstractDependencyResolutionTest {

    public void "cache handles manual deletion of cached artifacts"() {
        server.start()

        given:
        def module = ivyHttpRepo.module('group', 'projectA', '1.2').publish()

        def cacheDir = distribution.userHomeDir.file('caches').toURI()

        and:
        buildFile << """
repositories {
    ivy { url "${ivyHttpRepo.uri}" }
}
configurations { compile }
dependencies { compile 'group:projectA:1.2' }
task listJars << {
    assert configurations.compile.collect { it.name } == ['projectA-1.2.jar']
}
task deleteCacheFiles(type: Delete) {
    delete fileTree(dir: '${cacheDir}', includes: ['**/projectA/**'])
}
"""

        and:
        module.allowAll()

        and:
        succeeds('listJars')
        succeeds('deleteCacheFiles')
        
        when:
        server.resetExpectations()
        module.expectIvyGet()
        module.expectJarGet()

        then:
        succeeds('listJars')
    }

    public void "cache entries are segregated between different repositories"() {
        server.start()
        given:
        def repo1 = ivyHttpRepo('ivy-repo-a')
        def module1 = repo1.module('org.gradle', 'testproject', '1.0').publish()
        def repo2 = ivyHttpRepo('ivy-repo-b')
        def module2 = repo2.module('org.gradle', 'testproject', '1.0').publishWithChangedContent()

        and:
        settingsFile << "include 'a','b'"
        buildFile << """
subprojects {
    configurations {
        test
    }
    dependencies {
        test "org.gradle:testproject:1.0"
    }
    task retrieve(type: Sync) {
        into 'build'
        from configurations.test
    }
}
project('a') {
    repositories {
        ivy { url "${repo1.uri}" }
    }
}
project('b') {
    repositories {
        ivy { url "${repo2.uri}" }
    }
    retrieve.dependsOn(':a:retrieve')
}
"""

        when:
        module1.expectIvyGet()
        module1.expectJarGet()

        module2.expectIvyHead()
        module2.expectIvySha1Get()
        module2.expectIvyGet()
        module2.expectJarHead()
        module2.expectJarSha1Get()
        module2.expectJarGet()

        then:
        succeeds 'retrieve'

        and:
        file('a/build/testproject-1.0.jar').assertIsCopyOf(module1.jarFile)
        file('b/build/testproject-1.0.jar').assertIsCopyOf(module2.jarFile)
    }
}
