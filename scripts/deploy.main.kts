#!/usr/bin/env kotlin

/*
 * Copyright 2020 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Ported from kscript to the kotlin `*.main.kts` scripting form and relocated to scripts/ (ARCH-006).
 * The pure decision + assembly logic lives in scripts/lib/DeployCommand.kts and is unit-tested by
 * scripts/deploy.test.main.kts. This file keeps only the process orchestration (bazel build, mvn).
 *
 * Run from the workspace root:  ./scripts/deploy.main.kts [--key <gpgkey>] [-v]
 * (or: kotlin scripts/deploy.main.kts ...). Requires `kotlin`, `mvn`, and a local `bazel`/`bazelisk`
 * on PATH.
 *
 * PUBLISH TARGETS (ARCH-006 Central Portal migration):
 *   - Snapshots (CI on main) and the local fake sink deploy with `mvn deploy-file`. The snapshot URL
 *     now points at the Central Portal snapshot repository; OSSRH (oss.sonatype.org) reached EOL
 *     2025-06-30 and is gone (see DeployCommand.Target.MvnRepo).
 *   - Releases (a release-* branch with a --key and a non-snapshot version) select the Central Portal
 *     Publisher API bundle upload. The bundle assembly + HTTP upload is NOT wired up in this file yet
 *     — it lands in the follow-up PR to ARCH-006; a release invocation here fails loudly rather than
 *     pretending to publish.
 *
 * KNOWN LIMITATIONS carried forward:
 *   - CI detection keys off the Travis envvars (TRAVIS / TRAVIS_BRANCH); CI is GitHub Actions now, so
 *     the `--travis` snapshot path is effectively dead until rewired.
 *   - clikt is pinned at 2.6.0 (the original's version); newer geekinasuit scripts use clikt 4.x.
 *
 * FIXED relative to the original kscript:
 *   - The GPG key name (`-Dgpg.keyname=...`) was computed but never appended to the mvn command (a
 *     missing `+`); it is now always present when --key is supplied (see DeployCommand.buildMvnCommand).
 *   - A failed/timed-out `mvn` publish used to exit 0; it now logs to stderr and exits non-zero.
 *   - `branch` is trimmed before comparison, so a trailing newline from `git branch --show-current`
 *     no longer defeats the `== "main"` check (see DeployCommand.selectTarget).
 */
@file:Repository("https://repo1.maven.org/maven2")
@file:DependsOn("com.github.ajalt:clikt:2.6.0")
@file:DependsOn("com.squareup.moshi:moshi:1.15.1")
@file:Import("lib/DeployCommand.kts")

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import java.io.File
import java.io.IOException
import java.lang.ProcessBuilder.Redirect.INHERIT
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.system.exitProcess

class Main : CliktCommand() {
  private val key by option("-k", "--key", help = "GPG Key for deployment to sonatype")
  private val travis by option(envvar = "TRAVIS", help = "Operate with CI behavior").flag()
  private val branch by option(envvar = "TRAVIS_BRANCH", help = "Branch name override")
      .default("git branch --show-current".execute().stdout)
  private val verbose by option("-v", "-verbose").flag()

  private val username by option(hidden = true, envvar = "CI_DEPLOY_USERNAME")
  private val password by option(hidden = true, envvar = "CI_DEPLOY_PASSWORD")

  private val bazel = "bazel".which()

  private val version = DeployCommand.extractStringVariable(
      File("versions.bzl").readText(), "LIBRARY_VERSION"
  ).also {
    if (it.isEmpty()) {
      System.err.println("Could not extract version from version file.")
      exitProcess(1)
    }
  }
  private val snapshotVersion = version.endsWith("-SNAPSHOT")

  val pom_file = "$bazel build //tools/release:pom".execAndFilterSuffix(".pom")
  val artifact_file = "$bazel build //tools/release:deployment_jar".execAndFilterSuffix(".jar")
  val sources_file = "$bazel build //tools/release:sources_jar".execAndFilterSuffix("-sources.jar")
  val javadoc_file = "tools/release/placeholder-javadoc.jar"

  override fun run() {
    if (!File("WORKSPACE").exists()) {
      System.err.println("Must run deployment script from the workspace root.")
      exitProcess(1)
    }
    val target = when (val decision =
        DeployCommand.selectTarget(
            ci = travis,
            branch = branch,
            hasKey = key != null,
            snapshotVersion = snapshotVersion,
            version = version,
        )) {
      is DeployCommand.TargetDecision.Deploy -> decision.target
      is DeployCommand.TargetDecision.Abort -> throw PrintMessage(decision.message)
      is DeployCommand.TargetDecision.Usage -> throw UsageError(decision.message)
    }

    when (target) {
      is DeployCommand.Target.MvnRepo -> deployViaMvn(target)
      is DeployCommand.Target.CentralPortalRelease -> {
        // The bundle assembly + Publisher API upload lands in the follow-up PR to ARCH-006. Fail
        // loudly (non-zero) rather than exit 0 as if a release had been published.
        System.err.println(
            "Release upload to the Central Portal is not yet implemented (follow-up PR to ARCH-006). " +
                "Selected publishingType=${target.publishingType}; nothing was uploaded."
        )
        exitProcess(1)
      }
    }
  }

  /** Deploy to a plain Maven repository (snapshots / local fake) with `mvn deploy-file`. */
  private fun deployViaMvn(repo: DeployCommand.Target.MvnRepo) {
    val mvn_cmd = DeployCommand.buildMvnCommand(
        repo = repo,
        artifactFile = artifact_file,
        pomFile = pom_file,
        sourcesFile = sources_file,
        javadocFile = javadoc_file,
        key = key,
        verbose = verbose,
        settingsFile = "tools/release/settings.xml",
        snapshotVersion = snapshotVersion,
    )

    echo("Deploying version $version to $repo")
    if (verbose) echo("Executing command: ${mvn_cmd.joinToString(" ")}")
    ProcessBuilder(mvn_cmd)
        .directory(File("."))
        .redirectOutput(INHERIT)
        .redirectError(INHERIT)
        .apply {
          with(environment()) {
            if (repo != DeployCommand.Target.MvnRepo.FakeLocalRepo) {
              if (username != null && password != null) {
                putIfAbsent("CI_DEPLOY_USERNAME", username)
                putIfAbsent("CI_DEPLOY_PASSWORD", password)
              } else {
                throw UsageError("Must supply either CI_DEPLOY_USERNAME/CI_DEPLOY_PASSWORD " +
                    "environment variables, or override --username/--password to deploy to a " +
                    "non-fake repo.")
              }
            }
          }
        }
        .execute(
            timeout = 300,
            onTimeout = {
              System.err.println("Deployment timed out after 300s. Command: ${mvn_cmd.joinToString(" ")}")
              it.destroyForcibly()
              exitProcess(1)
            },
            onError = {
              System.err.println(
                  "Deployment failed: mvn exited ${it.exitValue()}. Command: ${mvn_cmd.joinToString(" ")}"
              )
              exitProcess(it.exitValue())
            }
        )
  }
}

/** Run a bazel build command and return the first output line ending with [suffix] (the artifact path). */
fun String.execAndFilterSuffix(suffix: String) =
    cmd()
        .execute()
        .let { proc ->
          if (proc.isAlive) throw TimeoutException("Should not still be running.")
          if (proc.exitValue() != 0) {
            val error = """Error executing command.
                Stdout: ${proc.stdout}
                Stderr: ${proc.stderr}
                """.trimIndent()
            throw IllegalStateException(error)
          }
          proc.stdout + proc.stderr
        }
        .let { DeployCommand.firstLineEndingWith(it, suffix) }

fun Process.wait(timeout: Long = 120, unit: TimeUnit = TimeUnit.SECONDS): Process =
    this.also { it.waitFor(timeout, unit) }

val Process.stderr: String get() = errorStream.bufferedReader().readText()

val Process.stdout: String get() = inputStream.bufferedReader().readText()

/** Short-cut which creates the command and executes it directly */
fun String.execute(
  timeout: Long = 120,
  unit: TimeUnit = TimeUnit.SECONDS,
  onTimeout: (Process) -> Unit = {},
  onError: (Process) -> Unit = {}
) = cmd().execute(timeout, unit, onTimeout, onError)

fun String.cmd(
  workingDir: File = File("."),
  outputRedirect: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
  errorRedirect: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE
): ProcessBuilder {
  val parts = this.split("\\s".toRegex())
  return ProcessBuilder(*parts.toTypedArray())
      .directory(workingDir)
      .redirectOutput(outputRedirect)
      .redirectError(errorRedirect)
}

fun ProcessBuilder.execute(
  timeout: Long = 120,
  unit: TimeUnit = TimeUnit.SECONDS,
  onTimeout: (Process) -> Unit = {},
  onError: (Process) -> Unit = {}
): Process = start().wait(timeout, unit).apply {
  if (isAlive) onTimeout(this)
  else when (exitValue()) {
    0 -> {}
    else -> onError(this)
  }
}

/**
 * Wraps the unix `which` command, returning the first path entry for the given command.
 */
fun String.which() = "which $this"
    .execute()
    .stdout
    .trim()
    .also {
      if (it.isEmpty()) throw IOException("Could not locate $this binary")
    }

Main().main(args)
