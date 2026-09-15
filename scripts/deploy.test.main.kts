#!/usr/bin/env kotlin

@file:Repository("https://repo1.maven.org/maven2/")
@file:DependsOn("org.junit.jupiter:junit-jupiter-api:5.11.0")
@file:DependsOn("org.junit.jupiter:junit-jupiter-engine:5.11.0")
@file:DependsOn("org.junit.platform:junit-platform-launcher:1.11.0")
@file:DependsOn("com.google.truth:truth:1.4.4")
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
        repo: DeployCommand.Repo = DeployCommand.Repo.FakeLocalRepo,
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
        val c = cmd(repo = DeployCommand.Repo.SonatypeStaging, key = "ABC123", snapshotVersion = false)
        assertThat(c).contains("-Dgpg.keyname=ABC123")
        assertThat(c[1]).isEqualTo("gpg:sign-and-deploy-file")
    }

    @Test fun `the fake local repo omits the global settings flag`() {
        val c = cmd(repo = DeployCommand.Repo.FakeLocalRepo)
        assertThat(c).doesNotContain("-gs")
    }

    @Test fun `a real repo passes -gs and the settings path in order`() {
        val c = cmd(repo = DeployCommand.Repo.SonatypeSnapshots)
        assertThat(c).containsAtLeast("-gs", "tools/release/settings.xml").inOrder()
    }

    @Test fun `a snapshot version omits the javadoc flag`() {
        val flat = cmd(snapshotVersion = true).joinToString(" ")
        assertThat(flat).doesNotContain("-Djavadoc")
    }

    @Test fun `a release version includes the javadoc flag`() {
        val c = cmd(repo = DeployCommand.Repo.SonatypeStaging, key = "K", snapshotVersion = false)
        assertThat(c).contains("-Djavadoc=tools/release/placeholder-javadoc.jar")
    }

    @Test fun `verbose adds the mvn debug flag`() {
        assertThat(cmd(verbose = true)).contains("--debug")
        assertThat(cmd(verbose = false)).doesNotContain("--debug")
    }

    @Test fun `repository id and url come from the target repo`() {
        val c = cmd(repo = DeployCommand.Repo.FakeLocalRepo)
        assertThat(c).contains("-DrepositoryId=local-fake")
        assertThat(c).contains("-Durl=file:///tmp/fakerepo")
    }
}

class SelectRepoTest {
    @Test fun `ci on main deploys to snapshots`() {
        val d = DeployCommand.selectRepo(ci = true, branch = "main", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isEqualTo(DeployCommand.RepoDecision.Deploy(DeployCommand.Repo.SonatypeSnapshots))
    }

    // Regression guard for the untrimmed-branch quirk: git branch --show-current yields "main\n".
    @Test fun `ci on main with a trailing newline still deploys to snapshots`() {
        val d = DeployCommand.selectRepo(ci = true, branch = "main\n", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isEqualTo(DeployCommand.RepoDecision.Deploy(DeployCommand.Repo.SonatypeSnapshots))
    }

    @Test fun `ci off main aborts`() {
        val d = DeployCommand.selectRepo(ci = true, branch = "feature", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isInstanceOf(DeployCommand.RepoDecision.Abort::class.java)
    }

    @Test fun `a release branch without a key is a usage error`() {
        val d = DeployCommand.selectRepo(ci = false, branch = "release-1.0", hasKey = false, snapshotVersion = false, version = "1.0")
        assertThat(d).isInstanceOf(DeployCommand.RepoDecision.Usage::class.java)
    }

    @Test fun `a release branch with a snapshot version is a usage error`() {
        val d = DeployCommand.selectRepo(ci = false, branch = "release-1.0", hasKey = true, snapshotVersion = true, version = "1.0-SNAPSHOT")
        assertThat(d).isInstanceOf(DeployCommand.RepoDecision.Usage::class.java)
    }

    @Test fun `a release branch with a key and a real version deploys to staging`() {
        val d = DeployCommand.selectRepo(ci = false, branch = "release-1.0", hasKey = true, snapshotVersion = false, version = "1.0")
        assertThat(d).isEqualTo(DeployCommand.RepoDecision.Deploy(DeployCommand.Repo.SonatypeStaging))
    }

    @Test fun `any other branch deploys to the fake local repo`() {
        val d = DeployCommand.selectRepo(ci = false, branch = "wip", hasKey = false, snapshotVersion = true, version = "1-SNAPSHOT")
        assertThat(d).isEqualTo(DeployCommand.RepoDecision.Deploy(DeployCommand.Repo.FakeLocalRepo))
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
    SelectRepoTest::class.java,
    ExtractStringVariableTest::class.java,
    FirstLineEndingWithTest::class.java,
)
