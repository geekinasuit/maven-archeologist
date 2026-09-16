#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.junit.jupiter:junit-jupiter-api:5.11.0")
@file:DependsOn("org.junit.jupiter:junit-jupiter-engine:5.11.0")
@file:DependsOn("org.junit.platform:junit-platform-launcher:1.11.0")
@file:DependsOn("com.google.truth:truth:1.4.4")
@file:DependsOn("com.squareup.moshi:moshi:1.15.1")
@file:Import("lib/TestRunner.kts")
@file:Import("lib/DeployCommand.kts")

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MvnGoalTest {
    @Test fun `no key is an unsigned deploy-file`() {
        assertThat(DeployCommand.mvnGoal(hasKey = false)).isEqualTo("deploy:deploy-file")
    }

    @Test fun `a key switches to signed deploy`() {
        assertThat(DeployCommand.mvnGoal(hasKey = true)).isEqualTo("gpg:sign-and-deploy-file")
    }
}

class BuildMvnCommandTest {
    private fun cmd(
        repo: DeployCommand.Target.MvnRepo = DeployCommand.Target.MvnRepo.FakeLocalRepo,
        key: String? = null,
        verbose: Boolean = false,
        snapshotVersion: Boolean = true,
    ): List<String> = DeployCommand.buildMvnCommand(
        repo = repo,
        artifactFile = "bazel-bin/tools/release/a.jar",
        pomFile = "bazel-bin/tools/release/a.pom",
        sourcesFile = "bazel-bin/tools/release/a-sources.jar",
        javadocFile = "tools/release/placeholder-javadoc.jar",
        key = key,
        verbose = verbose,
        settingsFile = "tools/release/settings.xml",
        snapshotVersion = snapshotVersion,
    )

    @Test fun `command starts with mvn and the goal`() {
        val c = cmd()
        assertThat(c[0]).isEqualTo("mvn")
        assertThat(c[1]).isEqualTo("deploy:deploy-file")
    }

    @Test fun `no-key deploy omits the gpg keyname flag`() {
        val flat = cmd(key = null).joinToString(" ")
        assertThat(flat).doesNotContain("-Dgpg.keyname")
    }

    // Regression guard for the original kscript bug: -Dgpg.keyname was computed but never appended.
    @Test fun `a supplied key appends -Dgpg-keyname and switches the goal`() {
        val c = cmd(repo = DeployCommand.Target.MvnRepo.SonatypeSnapshots, key = "ABC123", snapshotVersion = false)
        assertThat(c).contains("-Dgpg.keyname=ABC123")
        assertThat(c[1]).isEqualTo("gpg:sign-and-deploy-file")
    }

    @Test fun `the fake local repo omits the global settings flag`() {
        val c = cmd(repo = DeployCommand.Target.MvnRepo.FakeLocalRepo)
        assertThat(c).doesNotContain("-gs")
    }

    @Test fun `a real repo passes -gs and the settings path in order`() {
        val c = cmd(repo = DeployCommand.Target.MvnRepo.SonatypeSnapshots)
        assertThat(c).containsAtLeast("-gs", "tools/release/settings.xml").inOrder()
    }

    @Test fun `a snapshot version omits the javadoc flag`() {
        val flat = cmd(snapshotVersion = true).joinToString(" ")
        assertThat(flat).doesNotContain("-Djavadoc")
    }

    @Test fun `a release version includes the javadoc flag`() {
        val c = cmd(repo = DeployCommand.Target.MvnRepo.SonatypeSnapshots, key = "K", snapshotVersion = false)
        assertThat(c).contains("-Djavadoc=tools/release/placeholder-javadoc.jar")
    }

    @Test fun `verbose adds the mvn debug flag`() {
        assertThat(cmd(verbose = true)).contains("--debug")
        assertThat(cmd(verbose = false)).doesNotContain("--debug")
    }

    @Test fun `repository id and url come from the target repo`() {
        val c = cmd(repo = DeployCommand.Target.MvnRepo.FakeLocalRepo)
        assertThat(c).contains("-DrepositoryId=local-fake")
        assertThat(c).contains("-Durl=file:///tmp/fakerepo")
    }

    // The snapshot endpoint moved from OSSRH (oss.sonatype.org, EOL 2025-06-30) to the Central
    // Portal snapshot repository; guard both the id (must match settings.xml) and the new URL.
    @Test fun `the snapshot repo targets the central portal snapshot url`() {
        val c = cmd(repo = DeployCommand.Target.MvnRepo.SonatypeSnapshots)
        assertThat(c).contains("-DrepositoryId=central-snapshots")
        assertThat(c).contains("-Durl=https://central.sonatype.com/repository/maven-snapshots/")
    }
}

class SelectTargetTest {
    @Test fun `ci on main deploys to snapshots`() {
        val d = DeployCommand.selectTarget(ci = true, branch = "main", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isEqualTo(DeployCommand.TargetDecision.Deploy(DeployCommand.Target.MvnRepo.SonatypeSnapshots))
    }

    // Regression guard for the untrimmed-branch quirk: git branch --show-current yields "main\n".
    @Test fun `ci on main with a trailing newline still deploys to snapshots`() {
        val d = DeployCommand.selectTarget(ci = true, branch = "main\n", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isEqualTo(DeployCommand.TargetDecision.Deploy(DeployCommand.Target.MvnRepo.SonatypeSnapshots))
    }

    @Test fun `ci off main aborts`() {
        val d = DeployCommand.selectTarget(ci = true, branch = "feature", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isInstanceOf(DeployCommand.TargetDecision.Abort::class.java)
    }

    @Test fun `a release branch without a key is a usage error`() {
        val d = DeployCommand.selectTarget(ci = false, branch = "release-1.0", hasKey = false, snapshotVersion = false, version = "1.0")
        assertThat(d).isInstanceOf(DeployCommand.TargetDecision.Usage::class.java)
    }

    @Test fun `a release branch with a snapshot version is a usage error`() {
        val d = DeployCommand.selectTarget(ci = false, branch = "release-1.0", hasKey = true, snapshotVersion = true, version = "1.0-SNAPSHOT")
        assertThat(d).isInstanceOf(DeployCommand.TargetDecision.Usage::class.java)
    }

    // The release branch now selects the Central Portal Publisher API, not an OSSRH staging repo.
    @Test fun `a release branch with a key and a real version selects a central portal release`() {
        val d = DeployCommand.selectTarget(ci = false, branch = "release-1.0", hasKey = true, snapshotVersion = false, version = "1.0")
        assertThat(d).isEqualTo(
            DeployCommand.TargetDecision.Deploy(
                DeployCommand.Target.CentralPortalRelease(DeployCommand.PublishingType.USER_MANAGED)
            )
        )
    }

    // The default publishing type is USER_MANAGED; an explicit choice flows through to the target.
    @Test fun `the release publishing type flows through to the target`() {
        val d = DeployCommand.selectTarget(
            ci = false, branch = "release-1.0", hasKey = true, snapshotVersion = false, version = "1.0",
            publishingType = DeployCommand.PublishingType.AUTOMATIC,
        )
        assertThat(d).isEqualTo(
            DeployCommand.TargetDecision.Deploy(
                DeployCommand.Target.CentralPortalRelease(DeployCommand.PublishingType.AUTOMATIC)
            )
        )
    }

    @Test fun `any other branch deploys to the fake local repo`() {
        val d = DeployCommand.selectTarget(ci = false, branch = "wip", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isEqualTo(DeployCommand.TargetDecision.Deploy(DeployCommand.Target.MvnRepo.FakeLocalRepo))
    }

    // CI is checked before the release-branch rule, so a CI build that happens to be on a release-*
    // branch aborts rather than selecting an irreversible Central Portal publish. Guards that ordering.
    @Test fun `ci on a release branch aborts rather than releasing`() {
        val d = DeployCommand.selectTarget(ci = true, branch = "release-1.0", hasKey = true, snapshotVersion = false, version = "1.0")
        assertThat(d).isInstanceOf(DeployCommand.TargetDecision.Abort::class.java)
    }
}

class BearerHeaderTest {
    // base64("u:p") == "dTpw"; the header is the token pair, colon-joined, base64'd, behind "Bearer ".
    @Test fun `builds a bearer header from the token pair`() {
        assertThat(DeployCommand.bearerHeader("u", "p")).isEqualTo("Bearer dTpw")
    }

    @Test fun `different credentials produce different headers`() {
        assertThat(DeployCommand.bearerHeader("a", "b"))
            .isNotEqualTo(DeployCommand.bearerHeader("a", "c"))
    }
}

class EndpointUrlTest {
    @Test fun `upload url carries the publishing type`() {
        assertThat(DeployCommand.uploadUrl(DeployCommand.PublishingType.USER_MANAGED))
            .isEqualTo("https://central.sonatype.com/api/v1/publisher/upload?publishingType=USER_MANAGED")
    }

    @Test fun `upload url adds an optional deployment name`() {
        assertThat(DeployCommand.uploadUrl(DeployCommand.PublishingType.AUTOMATIC, name = "central-bundle.zip"))
            .isEqualTo("https://central.sonatype.com/api/v1/publisher/upload?publishingType=AUTOMATIC&name=central-bundle.zip")
    }

    @Test fun `status url passes the deployment id as a query parameter`() {
        assertThat(DeployCommand.statusUrl("28570f16-da32-4c14-bd2e-c1acc0782365"))
            .isEqualTo("https://central.sonatype.com/api/v1/publisher/status?id=28570f16-da32-4c14-bd2e-c1acc0782365")
    }

    @Test fun `deployment url puts the id in the path`() {
        assertThat(DeployCommand.deploymentUrl("28570f16-da32-4c14-bd2e-c1acc0782365"))
            .isEqualTo("https://central.sonatype.com/api/v1/publisher/deployment/28570f16-da32-4c14-bd2e-c1acc0782365")
    }
}

class ParseUploadResponseTest {
    @Test fun `a valid 201 response is the deployment id, trimmed`() {
        val r = DeployCommand.parseUploadResponse("  28570f16-da32-4c14-bd2e-c1acc0782365\n", 201)
        assertThat(r.deploymentId).isEqualTo("28570f16-da32-4c14-bd2e-c1acc0782365")
        assertThat(r.error).isNull()
    }

    @Test fun `a non-201 status is a failure even with a body`() {
        val r = DeployCommand.parseUploadResponse("28570f16-da32-4c14-bd2e-c1acc0782365", 401)
        assertThat(r.deploymentId).isNull()
        assertThat(r.error).contains("401")
    }

    @Test fun `an empty 201 body is a failure`() {
        val r = DeployCommand.parseUploadResponse("   ", 201)
        assertThat(r.deploymentId).isNull()
        assertThat(r.error).isNotNull()
    }

    // A proxy/auth error page returned with a 200/201 must not be trusted as a deployment id, or
    // every subsequent status poll would silently 404 against garbage.
    @Test fun `an html error page behind a 201 is rejected`() {
        val r = DeployCommand.parseUploadResponse("<html><body>502 Bad Gateway</body></html>", 201)
        assertThat(r.deploymentId).isNull()
        assertThat(r.error).isNotNull()
    }

    @Test fun `a multi-line body is rejected`() {
        val r = DeployCommand.parseUploadResponse("abc\ndef", 201)
        assertThat(r.deploymentId).isNull()
    }

    @Test fun `an implausibly long body is rejected`() {
        val r = DeployCommand.parseUploadResponse("x".repeat(500), 201)
        assertThat(r.deploymentId).isNull()
    }
}

class MultipartFramingTest {
    @Test fun `preamble carries the boundary, field name, and filename`() {
        val (preamble, _) = DeployCommand.multipartBundleFraming("BOUND123", "bundle.zip")
        val text = String(preamble, Charsets.UTF_8)
        assertThat(text).isEqualTo(
            "--BOUND123\r\n" +
                "Content-Disposition: form-data; name=\"bundle\"; filename=\"bundle.zip\"\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n"
        )
    }

    @Test fun `epilogue closes the boundary`() {
        val (_, epilogue) = DeployCommand.multipartBundleFraming("BOUND123", "bundle.zip")
        assertThat(String(epilogue, Charsets.UTF_8)).isEqualTo("\r\n--BOUND123--\r\n")
    }

    @Test fun `content type header echoes the boundary`() {
        assertThat(DeployCommand.multipartContentType("BOUND123"))
            .isEqualTo("multipart/form-data; boundary=BOUND123")
    }
}

class BundleLayoutTest {
    private val entries = DeployCommand.bundleEntries("com.example.foo", "widget", "2.3.4")

    @Test fun `group id dots become path slashes`() {
        assertThat(DeployCommand.groupPath("com.example.foo")).isEqualTo("com/example/foo")
    }

    @Test fun `maven file name includes the classifier only when present`() {
        assertThat(DeployCommand.mavenFileName("widget", "2.3.4", null, "jar")).isEqualTo("widget-2.3.4.jar")
        assertThat(DeployCommand.mavenFileName("widget", "2.3.4", "sources", "jar"))
            .isEqualTo("widget-2.3.4-sources.jar")
    }

    @Test fun `there are exactly four primary artifacts`() {
        assertThat(entries).hasSize(4)
    }

    @Test fun `the primaries are pom, jar, sources, and javadoc in upload order`() {
        assertThat(entries.map { it.fileName }).containsExactly(
            "widget-2.3.4.pom",
            "widget-2.3.4.jar",
            "widget-2.3.4-sources.jar",
            "widget-2.3.4-javadoc.jar",
        ).inOrder()
    }

    @Test fun `each primary sits in the maven-layout directory`() {
        assertThat(entries.first().bundlePath).isEqualTo("com/example/foo/widget/2.3.4/widget-2.3.4.pom")
    }

    // Central requires exactly .asc + .md5 + .sha1 per primary; the requirement does NOT recurse
    // (checksums aren't signed, signatures aren't checksummed) and sha256/sha512 are skipped.
    @Test fun `each primary has exactly asc, md5, and sha1 siblings`() {
        for (e in entries) {
            assertThat(e.siblings).containsExactly(
                "${e.bundlePath}.asc",
                "${e.bundlePath}.md5",
                "${e.bundlePath}.sha1",
            ).inOrder()
        }
    }

    @Test fun `the bundle is sixteen files with no optional checksums`() {
        val allFiles = entries.flatMap { listOf(it.bundlePath) + it.siblings }
        assertThat(allFiles).hasSize(16)
        assertThat(allFiles.none { it.endsWith(".sha256") || it.endsWith(".sha512") }).isTrue()
    }
}

class StatusParseTest {
    @Test fun `parses a validated state`() {
        val r = DeployCommand.parseStatusResponse("""{"deploymentId":"x","deploymentState":"VALIDATED"}""")
        assertThat(r.state).isEqualTo(DeployCommand.DeploymentState.VALIDATED)
        assertThat(r.rawState).isEqualTo("VALIDATED")
        assertThat(r.errors).isNull()
    }

    @Test fun `parses a published state`() {
        val r = DeployCommand.parseStatusResponse("""{"deploymentState":"PUBLISHED","purls":["pkg:maven/x@1"]}""")
        assertThat(r.state).isEqualTo(DeployCommand.DeploymentState.PUBLISHED)
    }

    @Test fun `surfaces the errors field on failure`() {
        val r = DeployCommand.parseStatusResponse(
            """{"deploymentState":"FAILED","errors":{"widget-2.3.4.pom":["missing signature"]}}"""
        )
        assertThat(r.state).isEqualTo(DeployCommand.DeploymentState.FAILED)
        assertThat(r.errors).contains("missing signature")
    }

    // An unrecognized state string is preserved in rawState (surfaced, not swallowed); state is null.
    @Test fun `preserves an unknown state string`() {
        val r = DeployCommand.parseStatusResponse("""{"deploymentState":"WARP_SPEED"}""")
        assertThat(r.state).isNull()
        assertThat(r.rawState).isEqualTo("WARP_SPEED")
    }

    @Test fun `a missing state is null`() {
        val r = DeployCommand.parseStatusResponse("""{"deploymentId":"x"}""")
        assertThat(r.state).isNull()
        assertThat(r.rawState).isNull()
    }

    // A poller must not die on a malformed/partial body; parsing yields an empty result, not a throw.
    @Test fun `a malformed body parses to an empty result without throwing`() {
        val r = DeployCommand.parseStatusResponse("this is not json")
        assertThat(r.state).isNull()
        assertThat(r.rawState).isNull()
        assertThat(r.errors).isNull()
    }
}

class PollDispositionTest {
    private val userManaged = DeployCommand.PublishingType.USER_MANAGED
    private val automatic = DeployCommand.PublishingType.AUTOMATIC

    // The core guard: USER_MANAGED succeeds at VALIDATED and must NOT wait for PUBLISHED (which
    // never arrives without a manual publish), while AUTOMATIC's success is PUBLISHED.
    @Test fun `terminal success state differs by publishing type`() {
        assertThat(DeployCommand.terminalSuccessState(userManaged)).isEqualTo(DeployCommand.DeploymentState.VALIDATED)
        assertThat(DeployCommand.terminalSuccessState(automatic)).isEqualTo(DeployCommand.DeploymentState.PUBLISHED)
    }

    @Test fun `user-managed is done at validated but automatic keeps waiting`() {
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.VALIDATED, userManaged))
            .isEqualTo(DeployCommand.PollDisposition.SUCCESS)
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.VALIDATED, automatic))
            .isEqualTo(DeployCommand.PollDisposition.WAIT)
    }

    @Test fun `automatic succeeds at published`() {
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.PUBLISHED, automatic))
            .isEqualTo(DeployCommand.PollDisposition.SUCCESS)
    }

    // Past validation already: for user-managed the tool's job (upload + validate) is done, so
    // PUBLISHING counts as success; for automatic it is still in flight toward PUBLISHED.
    @Test fun `publishing is success for user-managed but keeps waiting for automatic`() {
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.PUBLISHING, userManaged))
            .isEqualTo(DeployCommand.PollDisposition.SUCCESS)
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.PUBLISHING, automatic))
            .isEqualTo(DeployCommand.PollDisposition.WAIT)
    }

    @Test fun `failed is failure for either type`() {
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.FAILED, userManaged))
            .isEqualTo(DeployCommand.PollDisposition.FAILED)
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.FAILED, automatic))
            .isEqualTo(DeployCommand.PollDisposition.FAILED)
    }

    @Test fun `pending and validating keep waiting`() {
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.PENDING, automatic))
            .isEqualTo(DeployCommand.PollDisposition.WAIT)
        assertThat(DeployCommand.classifyPoll(DeployCommand.DeploymentState.VALIDATING, userManaged))
            .isEqualTo(DeployCommand.PollDisposition.WAIT)
    }

    // A null (unparseable) state keeps the poll alive; the caller's overall timeout bounds it.
    @Test fun `a null state keeps waiting`() {
        assertThat(DeployCommand.classifyPoll(null, userManaged)).isEqualTo(DeployCommand.PollDisposition.WAIT)
    }
}

class DigestTest {
    @Test fun `hex encodes low bytes without sign extension`() {
        assertThat(DeployCommand.hex(byteArrayOf(0, 15, 16, 255.toByte()))).isEqualTo("000f10ff")
    }

    @Test fun `md5 matches known vectors`() {
        assertThat(DeployCommand.md5Hex("".toByteArray())).isEqualTo("d41d8cd98f00b204e9800998ecf8427e")
        assertThat(DeployCommand.md5Hex("abc".toByteArray())).isEqualTo("900150983cd24fb0d6963f7d28e17f72")
    }

    @Test fun `sha1 matches known vectors`() {
        assertThat(DeployCommand.sha1Hex("".toByteArray())).isEqualTo("da39a3ee5e6b4b0d3255bfef95601890afd80709")
        assertThat(DeployCommand.sha1Hex("abc".toByteArray())).isEqualTo("a9993e364706816aba3e25717850c26c9cd0d89d")
    }
}

class ExtractStringVariableTest {
    @Test fun `reads a simple quoted value`() {
        assertThat(DeployCommand.extractStringVariable("LIBRARY_VERSION = \"HEAD-SNAPSHOT\"", "LIBRARY_VERSION"))
            .isEqualTo("HEAD-SNAPSHOT")
    }

    @Test fun `drops a trailing comment`() {
        val text = "LIBRARY_VERSION = \"1.2.3\"  # Do not refactor without altering the deploy script"
        assertThat(DeployCommand.extractStringVariable(text, "LIBRARY_VERSION")).isEqualTo("1.2.3")
    }

    @Test fun `picks the named variable out of a multi-line file`() {
        val text = "OTHER = \"nope\"\nLIBRARY_VERSION = \"9.9\"\nMORE = \"nope\""
        assertThat(DeployCommand.extractStringVariable(text, "LIBRARY_VERSION")).isEqualTo("9.9")
    }
}

class ExtractKwargStringTest {
    @Test fun `reads an indented kwarg with a trailing comma`() {
        val text = "metadata(\n    group_id = \"com.geekinasuit\",\n    artifact_id = \"maven-archeologist\",\n)"
        assertThat(DeployCommand.extractKwargString(text, "group_id")).isEqualTo("com.geekinasuit")
        assertThat(DeployCommand.extractKwargString(text, "artifact_id")).isEqualTo("maven-archeologist")
    }

    // A key that is a prefix of another (group_id / group_id_extra) must not cross-match.
    @Test fun `does not match a longer key sharing the same prefix`() {
        val text = "    group_id_extra = \"wrong\",\n    group_id = \"right\",\n"
        assertThat(DeployCommand.extractKwargString(text, "group_id")).isEqualTo("right")
    }
}

class FirstLineEndingWithTest {
    // Bazel indents artifact lines (leading whitespace) but does not trail them; the function
    // matches endsWith on the raw line, then trims the leading indent off the result.
    @Test fun `returns the trimmed line ending with the suffix`() {
        val output = "INFO: building\n  bazel-bin/tools/release/a.pom\nINFO: done"
        assertThat(DeployCommand.firstLineEndingWith(output, ".pom"))
            .isEqualTo("bazel-bin/tools/release/a.pom")
    }

    // The match is a plain endsWith, so ".jar" also matches "a-sources.jar" and returns the FIRST
    // such line. This is only unambiguous at the real call sites because deploy.main.kts runs a
    // separate `bazel build` per target (deployment_jar vs sources_jar), so each output contains
    // just one candidate — the suffixes are not relied on to separate jar from sources within one blob.
    @Test fun `distinguishes jar from sources jar by suffix`() {
        val output = "bazel-bin/x/a-sources.jar\nbazel-bin/x/a.jar"
        assertThat(DeployCommand.firstLineEndingWith(output, "-sources.jar")).isEqualTo("bazel-bin/x/a-sources.jar")
        assertThat(DeployCommand.firstLineEndingWith(output, ".jar")).isEqualTo("bazel-bin/x/a-sources.jar")
    }
}

runTests(
    MvnGoalTest::class.java,
    BuildMvnCommandTest::class.java,
    SelectTargetTest::class.java,
    BearerHeaderTest::class.java,
    EndpointUrlTest::class.java,
    ParseUploadResponseTest::class.java,
    MultipartFramingTest::class.java,
    BundleLayoutTest::class.java,
    StatusParseTest::class.java,
    PollDispositionTest::class.java,
    DigestTest::class.java,
    ExtractStringVariableTest::class.java,
    ExtractKwargStringTest::class.java,
    FirstLineEndingWithTest::class.java,
)
