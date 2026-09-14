# Copyright (C) 2020 Square, Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
# in compliance with the License. You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software distributed under the License
# is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
# or implied. See the License for the specific language governing permissions and limitations under
# the License.

# Main library metadata:
# library version - change in release branches. This should always be "HEAD-SNAPSHOT" at main HEAD
load("//tools/release:release_metadata.bzl", "developer", "metadata")

LIBRARY_VERSION = "HEAD-SNAPSHOT"  # Don't refactor this without altering tools/deploy.kts
LIBRARY_METADATA = metadata(
    name = "Maven Archeologist",
    description = "A thin API for resolving and downloading Maven artifacts and metadata",
    group_id = "com.squareup.tools.build",
    artifact_id = "maven-archeologist",
    # library version - change in release branches.
    # This should always be "HEAD-SNAPSHOT" at main HEAD
    version = LIBRARY_VERSION,
    target = "//src/main/java/com/squareup/tools/maven/resolution",
    license = "Apache-2.0",  # SPDX token for Apache 2.0
    github_slug = "square/maven-archeologist",
    developers = [developer("cgruber", "Christian Gruber", "gruber@squareup.com")],
)

# What language compliance levels are we configuring
JAVA_LANGUAGE_LEVEL = "1.8"
KOTLIN_LANGUAGE_LEVEL = "1.5"

# What version of kotlin are we using. kotlin_repositories() uses the rules' default kotlinc
# (1.9.23 for rules_kotlin v1.9.6), so no explicit compiler pin is needed; this constant drives
# the kotlin-stdlib/kotlin-reflect maven coordinates below, which must match the compiler.
KOTLIN_VERSION = "1.9.23"

# what version of the kotlin rules are we using
# ARCH-002: bumped v1.6.0-RC-2 -> v1.9.6 (last 1.x; WORKSPACE mode on Bazel 7.7.1, mirrors the
# bazel_maven_repository fork). Release asset name changed to rules_kotlin-<version>.tar.gz.
KOTLIN_RULES_VERSION = "v1.9.6"
KOTLIN_RULES_FORK = "bazelbuild"
KOTLIN_RULES_SHA = "3b772976fec7bdcda1d84b9d39b176589424c047eb2175bed09aac630e50af43"
KOTLIN_RULES_URL = "https://github.com/{fork}/rules_kotlin/releases/download/{version}/rules_kotlin-{version}.tar.gz".format(
    fork = KOTLIN_RULES_FORK,
    version = KOTLIN_RULES_VERSION,
)

# ARCH-002: rules_java pinned ahead of rules_kotlin. rules_kotlin 1.9.6's kotlin_repositories()
# otherwise pulls a rules_java too old for Bazel 7.7.1's test infra (lcov_merger loads
# @rules_java//java:java_binary.bzl). See WORKSPACE for the load ordering that makes this win.
RULES_JAVA_VERSION = "7.6.5"
RULES_JAVA_SHA = "8afd053dd2a7b85a4f033584f30a7f1666c5492c56c76e04eec4428bdb2a86cf"
RULES_JAVA_URL = "https://github.com/bazelbuild/rules_java/releases/download/{version}/rules_java-{version}.tar.gz".format(
    version = RULES_JAVA_VERSION,
)

# ARCH-002: repointed from square's 2.0.0-alpha-5 (fails on Bazel 7: "invalid user-provided repo
# name ''") to the geekinasuit fork, which was modernized onto Bazel 7.7.1. No tagged release
# exists yet, so pinned by commit SHA on the fork's master branch.
MAVEN_REPOSITORY_RULES_FORK = "geekinasuit"
MAVEN_REPOSITORY_RULES_VERSION = "1b8716aa5184950a6cb067baaf566f6f4f621850"
MAVEN_REPOSITORY_RULES_SHA = "4cb32bf4819ae3d60d71800682ccda8ccde00bfc0dd63756cb4b960853da5541"

MAVEN_LIBRARY_VERSION = "3.6.3"

DIRECT_ARTIFACTS = {
    "com.github.ajalt:clikt:2.6.0": {"insecure": True},
    "com.google.truth:truth:1.0": {
        "insecure": True,
        "testonly": True,
        "exclude": ["com.google.auto.value:auto-value-annotations"],
    },
    "com.google.guava:guava:27.1-jre": {
        "insecure": True,
        "testonly": True,
        "exclude": ["com.google.guava:failureaccess", "com.google.guava:listenablefuture"],
    },
    "com.squareup.okhttp3:okhttp:4.7.2": {"insecure": True},
    "com.squareup.okio:okio:2.6.0": {"insecure": True},
    "com.squareup.moshi:moshi:1.9.3": {"insecure": True},
    "com.squareup.moshi:moshi-kotlin:1.9.3": {"insecure": True},
    "org.apache.maven:maven-artifact:%s" % MAVEN_LIBRARY_VERSION: {"insecure": True},
    "org.apache.maven:maven-builder-support:%s" % MAVEN_LIBRARY_VERSION: {"insecure": True},
    "org.apache.maven:maven-model:%s" % MAVEN_LIBRARY_VERSION: {"insecure": True},
    "org.apache.maven:maven-model-builder:%s" % MAVEN_LIBRARY_VERSION: {
        "insecure": True,
        "exclude": ["javax.inject:javax.inject", "org.eclipse.sisu:org.eclipse.sisu.inject"],
    },
    "junit:junit:4.13": {"insecure": True, "testonly": True},
}

TRANSITIVE_ARTIFACTS = [
    "com.google.code.findbugs:jsr305:3.0.2",
    "com.google.errorprone:error_prone_annotations:2.3.1",
    "com.google.guava:failureaccess:1.0.1",
    "com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava",
    "com.google.j2objc:j2objc-annotations:1.1",
    "com.googlecode.java-diff-utils:diffutils:1.3.0",
    "org.apache.commons:commons-lang3:3.8.1",
    "org.checkerframework:checker-compat-qual:2.5.5",
    "org.checkerframework:checker-qual:2.5.2",
    "org.codehaus.mojo:animal-sniffer-annotations:1.17",
    "org.codehaus.plexus:plexus-interpolation:1.26",
    "org.codehaus.plexus:plexus-utils:3.3.0",
    "org.hamcrest:hamcrest-core:1.3",
    "org.jetbrains.kotlin:kotlin-stdlib-common:%s" % KOTLIN_VERSION,
    "org.jetbrains.kotlin:kotlin-stdlib:%s" % KOTLIN_VERSION,
    "org.jetbrains.kotlin:kotlin-reflect:%s" % KOTLIN_VERSION,
    "org.jetbrains:annotations:13.0",
]

def maven_artifacts():
    artifacts = {}
    artifacts.update(DIRECT_ARTIFACTS)
    for artifact in TRANSITIVE_ARTIFACTS:
        artifacts.update({artifact: {"insecure": True}})
    return artifacts
