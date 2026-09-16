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
 *   - Releases (a release-* branch with a --key and a non-snapshot version) sign the 4 primary
 *     artifacts (gpg --detach-sign), compute .md5/.sha1 companions, bundle all 16 files in Maven
 *     layout, and upload the zip to the Central Portal Publisher API, then poll until the upload
 *     reaches VALIDATED (USER_MANAGED) or PUBLISHED (AUTOMATIC). It does NOT call the publish or
 *     drop endpoints: under USER_MANAGED a human publishes the validated deployment by hand in the
 *     Portal UI. The upload+poll round-trip is unverified against a live Central Portal deployment
 *     until an operator runs it with a real token (see ARCH-006).
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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

  private val versionsBzl = File("versions.bzl").readText()

  private val version = DeployCommand.extractStringVariable(versionsBzl, "LIBRARY_VERSION").also {
    if (it.isEmpty()) {
      System.err.println("Could not extract version from version file.")
      exitProcess(1)
    }
  }
  private val snapshotVersion = version.endsWith("-SNAPSHOT")
  // Only the release path needs these, so compute them lazily: an unexpected versions.bzl shape
  // then fails a release (where the operator is present) rather than breaking every snapshot deploy.
  private val groupId by lazy { DeployCommand.extractKwargString(versionsBzl, "group_id") }
  private val artifactId by lazy { DeployCommand.extractKwargString(versionsBzl, "artifact_id") }

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

    // The script host does not enforce sealed-`when` exhaustiveness the way a compiled .kt file
    // does (verified: kotlinc errors on a non-exhaustive `when` here; `kotlin *.main.kts` on the
    // same code silently runs it) — so an unhandled Target subtype must fail loudly here rather
    // than silently falling through.
    when (target) {
      is DeployCommand.Target.MvnRepo -> deployViaMvn(target)
      is DeployCommand.Target.CentralPortalRelease -> deployViaCentralPortal(target)
      else -> error("Unhandled DeployCommand.Target subtype: ${target::class}")
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

  /**
   * Sign, checksum, bundle, and upload a release to the Central Portal Publisher API, then poll
   * until the upload reaches a terminal state (VALIDATED for USER_MANAGED, PUBLISHED for
   * AUTOMATIC — see DeployCommand.classifyPoll). Does not call the publish or drop endpoints:
   * under USER_MANAGED the deployment stops at VALIDATED and a human publishes it by hand in the
   * Portal UI (or via a separate, deliberate API call) — auto-publishing here would remove that
   * checkpoint on this repo's first-ever release.
   */
  private fun deployViaCentralPortal(target: DeployCommand.Target.CentralPortalRelease) {
    val gpgKey = requireNotNull(key) { "selectTarget guarantees --key is present for a release." }
    val tokenUser = username
        ?: throw UsageError("Must supply CI_DEPLOY_USERNAME to publish a release to the Central Portal.")
    val tokenPass = password
        ?: throw UsageError("Must supply CI_DEPLOY_PASSWORD to publish a release to the Central Portal.")

    val entries = DeployCommand.bundleEntries(groupId, artifactId, version)
    // Associate each built artifact with its bundle entry by (classifier, extension), not by list
    // position: a positional pairing would silently file artifact A's bytes under B's name and
    // checksums if the primaries and the source list ever drift out of step. A missing key fails
    // loudly below.
    val sourceByKey: Map<Pair<String?, String>, String> = mapOf(
        (null to "pom") to pom_file,
        (null to "jar") to artifact_file,
        ("sources" to "jar") to sources_file,
        ("javadoc" to "jar") to javadoc_file,
    )

    echo("Assembling Central Portal bundle for $groupId:$artifactId:$version (publishingType=${target.publishingType})")
    val client = HttpClient.newHttpClient()
    val stagingDir = Files.createTempDirectory("central-portal-bundle")
    val uploadResult = try {
      val zipFile = stagingDir.resolve("bundle.zip").toFile()
      ZipOutputStream(zipFile.outputStream()).use { zos ->
        entries.forEach { entry ->
          val sourcePath = requireNotNull(sourceByKey[entry.classifier to entry.extension]) {
            "No built artifact for bundle entry ${entry.fileName} " +
                "(classifier=${entry.classifier}, extension=${entry.extension})."
          }
          val bytes = File(sourcePath).readBytes()
          val ascFile = stagingDir.resolve("${entry.fileName}.asc").toFile()
          gpgSign(File(sourcePath), gpgKey, ascFile)

          zos.putNextEntry(ZipEntry(entry.bundlePath))
          zos.write(bytes)
          zos.closeEntry()

          val (ascPath, md5Path, sha1Path) = entry.siblings
          zos.putNextEntry(ZipEntry(ascPath))
          zos.write(ascFile.readBytes())
          zos.closeEntry()

          zos.putNextEntry(ZipEntry(md5Path))
          zos.write(DeployCommand.md5Hex(bytes).toByteArray(Charsets.UTF_8))
          zos.closeEntry()

          zos.putNextEntry(ZipEntry(sha1Path))
          zos.write(DeployCommand.sha1Hex(bytes).toByteArray(Charsets.UTF_8))
          zos.closeEntry()
        }
      }

      echo("Uploading bundle (${zipFile.length()} bytes) to the Central Portal...")
      val boundary = "----GeekInASuitBoundary${UUID.randomUUID()}"
      val (preamble, epilogue) = DeployCommand.multipartBundleFraming(boundary, zipFile.name)
      val uploadRequest = HttpRequest.newBuilder(URI.create(DeployCommand.uploadUrl(target.publishingType)))
          .header("Authorization", DeployCommand.bearerHeader(tokenUser, tokenPass))
          .header("Content-Type", DeployCommand.multipartContentType(boundary))
          .timeout(Duration.ofMinutes(5))
          .POST(HttpRequest.BodyPublishers.ofByteArrays(listOf(preamble, zipFile.readBytes(), epilogue)))
          .build()
      val uploadResponse = client.send(uploadRequest, HttpResponse.BodyHandlers.ofString())
      DeployCommand.parseUploadResponse(uploadResponse.body(), uploadResponse.statusCode())
    } finally {
      // The staging dir holds the signed artifacts and the zip — none secret (the artifacts are
      // public; the token never lands here), but delete it so runs don't accumulate temp bundles.
      // Cleanup lives in finally, and the upload-failure exit is handled AFTER it, so System.exit
      // (which skips finally) never strands the temp dir.
      stagingDir.toFile().deleteRecursively()
    }

    val deploymentId = uploadResult.deploymentId ?: run {
      System.err.println("Central Portal upload failed: ${uploadResult.error}")
      exitProcess(1)
    }
    echo("Uploaded; deployment id=$deploymentId. Polling for validation...")
    pollUntilDone(client, deploymentId, target.publishingType, tokenUser, tokenPass)

    when (target.publishingType) {
      DeployCommand.PublishingType.USER_MANAGED -> echo(
          "Deployment $deploymentId is VALIDATED and awaiting manual publish: " +
              "https://central.sonatype.com/publishing/deployments " +
              "(or POST ${DeployCommand.deploymentUrl(deploymentId)})."
      )
      DeployCommand.PublishingType.AUTOMATIC -> echo("Deployment $deploymentId published.")
    }
  }

  /** GPG-sign [file] into [ascFile] with [key]. Signs the file verbatim — never a directory. */
  private fun gpgSign(file: File, key: String, ascFile: File) {
    val cmd = listOf(
        "gpg", "--batch", "--yes", "--armor", "--local-user", key,
        "--detach-sign", "--output", ascFile.path, file.path,
    )
    val process = ProcessBuilder(cmd).redirectErrorStream(true).start()
    // waitFor with a timeout FIRST, then read: reading to EOF before waiting would block forever if
    // gpg stalled without closing its output, defeating the timeout. detach-sign's diagnostic output
    // is far below the pipe buffer, so gpg can't deadlock on a full unread pipe before it exits.
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      throw IllegalStateException("gpg sign timed out for ${file.path}")
    }
    if (process.exitValue() != 0) {
      val output = process.inputStream.bufferedReader().readText()
      throw IllegalStateException("gpg sign failed for ${file.path} (exit ${process.exitValue()}): $output")
    }
  }

  /**
   * Poll the Publisher API's /status endpoint for [deploymentId] until DeployCommand.classifyPoll
   * reports SUCCESS or FAILED, or [timeoutSeconds] elapses. A single unparseable/missing-state
   * response is treated as WAIT (see DeployCommand.parseStatusResponse) so one bad read doesn't
   * abort the poll; this timeout is what bounds that tolerance.
   */
  private fun pollUntilDone(
      client: HttpClient,
      deploymentId: String,
      publishingType: DeployCommand.PublishingType,
      tokenUser: String,
      tokenPass: String,
      timeoutSeconds: Long = 300,
      intervalSeconds: Long = 10,
  ) {
    val deadline = System.currentTimeMillis() + timeoutSeconds * 1000
    var lastState = "unknown"
    while (true) {
      val request = HttpRequest.newBuilder(URI.create(DeployCommand.statusUrl(deploymentId)))
          .header("Authorization", DeployCommand.bearerHeader(tokenUser, tokenPass))
          // Bound each poll: HttpClient has no default request timeout, so without this a hung
          // /status connection would block send() forever and never reach the deadline check.
          .timeout(Duration.ofSeconds(30))
          .POST(HttpRequest.BodyPublishers.noBody())
          .build()
      // A single failed/slow read (timeout, connection reset) is treated as WAIT so one hiccup
      // doesn't abort the whole poll; the deadline below bounds how long that tolerance lasts.
      val disposition = try {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = DeployCommand.parseStatusResponse(response.body())
        lastState = if (status.rawState != null) "${status.rawState} (HTTP ${response.statusCode()})"
                    else "HTTP ${response.statusCode()}"
        val d = DeployCommand.classifyPoll(status.state, publishingType)
        if (d == DeployCommand.PollDisposition.FAILED) {
          System.err.println(
              "Deployment $deploymentId failed (state=${status.rawState}): " +
                  "${status.errors ?: "no error detail returned"}. " +
                  droppableNote(deploymentId)
          )
          exitProcess(1)
        }
        d
      } catch (e: IOException) {
        // HttpTimeoutException (a per-poll .timeout() firing) is an IOException, as is a connection
        // reset. Any single failed read is a transient WAIT; the overall deadline below bounds it.
        DeployCommand.PollDisposition.WAIT
      }
      if (disposition == DeployCommand.PollDisposition.SUCCESS) return
      if (System.currentTimeMillis() > deadline) {
        System.err.println(
            "Timed out after ${timeoutSeconds}s waiting for deployment $deploymentId " +
                "(last state: $lastState). ${droppableNote(deploymentId)}"
        )
        exitProcess(1)
      }
      Thread.sleep(intervalSeconds * 1000)
    }
  }

  /**
   * A note telling the operator how to clean up a deployment left behind by a failed or timed-out
   * run. The upload succeeded (we have an id), so the deployment exists in the Portal even though
   * this run did not reach a terminal success — it must be dropped by hand, since this tool
   * deliberately never calls the drop endpoint.
   */
  private fun droppableNote(deploymentId: String): String =
      "The deployment may remain in the Portal — drop it at " +
          "https://central.sonatype.com/publishing/deployments or via " +
          "DELETE ${DeployCommand.deploymentUrl(deploymentId)}."
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
