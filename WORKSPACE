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
workspace(name = "maven_archeologist")

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load(
    "//:versions.bzl",
    "KOTLIN_RULES_SHA",
    "KOTLIN_RULES_URL",
    "RULES_JAVA_SHA",
    "RULES_JAVA_URL",
    "MAVEN_REPOSITORY_RULES_FORK",
    "MAVEN_REPOSITORY_RULES_SHA",
    "MAVEN_REPOSITORY_RULES_VERSION",
    "maven_artifacts",
)

# ARCH-002: rules_java pinned AHEAD of rules_kotlin. rules_kotlin 1.9.6's kotlin_repositories()
# pulls a rules_java too old for Bazel 7.7.1's test infra (@bazel_tools//tools/test:lcov_merger
# loads @rules_java//java:java_binary.bzl, which the old one lacks). maybe() in
# kotlin_repositories() is first-wins, so declaring a Bazel-7-compatible rules_java here keeps it.
http_archive(
    name = "rules_java",
    sha256 = RULES_JAVA_SHA,
    urls = [RULES_JAVA_URL],
)

load("@rules_java//java:repositories.bzl", "rules_java_dependencies", "rules_java_toolchains")

rules_java_dependencies()

rules_java_toolchains()

# Load the kotlin rules repository, and setup kotlin rules and toolchain.
http_archive(
    name = "rules_kotlin",
    sha256 = KOTLIN_RULES_SHA,
    urls = [KOTLIN_RULES_URL],
)

load("@rules_kotlin//kotlin:repositories.bzl", "kotlin_repositories")

# No args: use the rules' default kotlinc (1.9.23 for v1.9.6).
kotlin_repositories()

register_toolchains("//:kotlin_toolchain")

http_archive(
    name = "maven_repository_rules",
    sha256 = MAVEN_REPOSITORY_RULES_SHA,
    strip_prefix = "bazel_maven_repository-%s" % MAVEN_REPOSITORY_RULES_VERSION,
    urls = ["https://github.com/%s/bazel_maven_repository/archive/%s.zip" % (MAVEN_REPOSITORY_RULES_FORK, MAVEN_REPOSITORY_RULES_VERSION)],
)

load("@maven_repository_rules//maven:maven.bzl", "maven_repository_specification")

maven_repository_specification(
    name = "maven",
    artifacts = maven_artifacts(),
    repository_urls = {"central": "https://repo1.maven.org/maven2"},
)
