/**
 * DeployCommand.kts — pure decision + command-assembly logic for the deploy script.
 *
 * Extracted from deploy.main.kts so the parts worth testing (which repo to target, how the
 * mvn command is assembled, how the version is parsed, how bazel output is filtered) are
 * unit-testable without shelling out to bazel/mvn. The main script keeps the process
 * orchestration; everything here is a pure function of its arguments.
 *
 * Import with @file:Import("lib/DeployCommand.kts").
 */

object DeployCommand {
    sealed class Repo(val id: String, val url: String) {
        object SonatypeSnapshots : Repo(
            "sonatype-nexus-snapshots",
            "https://oss.sonatype.org/content/repositories/snapshots"
        )
        object SonatypeStaging : Repo(
            "sonatype-nexus-staging",
            "https://oss.sonatype.org/service/local/staging/deploy/maven2"
        )
        object FakeLocalRepo : Repo(
            "local-fake",
            "file:///tmp/fakerepo"
        )
        override fun toString(): String = this::class.simpleName ?: "Repo"
    }

    /** Outcome of repo selection: proceed with a target, or stop with a message. */
    sealed class RepoDecision {
        data class Deploy(val repo: Repo) : RepoDecision()
        /** A non-error stop (surfaced by the caller as clikt PrintMessage) — e.g. CI off main. */
        data class Abort(val message: String) : RepoDecision()
        /** A user/config error (surfaced by the caller as clikt UsageError). */
        data class Usage(val message: String) : RepoDecision()
    }

    /**
     * Decide the deployment target from CI/branch/key/version state.
     *
     * `branch` is trimmed before comparison: it usually comes from `git branch --show-current`,
     * whose stdout carries a trailing newline, so an untrimmed `"main\n"` would never match "main".
     */
    fun selectRepo(
        ci: Boolean,
        branch: String,
        hasKey: Boolean,
        snapshotVersion: Boolean,
        version: String,
    ): RepoDecision {
        val b = branch.trim()
        return when {
            ci ->
                if (b == "main") RepoDecision.Deploy(Repo.SonatypeSnapshots)
                else RepoDecision.Abort("Aborting deployment on a non-main branch.")
            b.startsWith("release-") -> when {
                !hasKey -> RepoDecision.Usage("Must supply --key <gpgkey> for release deployments.")
                snapshotVersion -> RepoDecision.Usage(
                    "Don't use a snapshot version ($version) on a release branch ($b)"
                )
                else -> RepoDecision.Deploy(Repo.SonatypeStaging)
            }
            else -> RepoDecision.Deploy(Repo.FakeLocalRepo)
        }
    }

    /** The mvn goal: a signed deploy when a GPG key is supplied, a plain deploy otherwise. */
    fun mvnGoal(hasKey: Boolean): String =
        if (hasKey) "gpg:sign-and-deploy-file" else "deploy:deploy-file"

    /**
     * Assemble the mvn invocation as an argv list.
     *
     * Returning a list (not a single space-joined string that the caller re-splits on whitespace)
     * keeps a path containing a space intact, and makes the assembly directly assertable.
     *
     * `key`: the GPG key name, or null for an unsigned deploy. THIS is where the original kscript
     * had a bug — `-Dgpg.keyname=<key>` was computed but never appended to the command (a missing
     * `+` in the string concatenation), so `gpg:sign-and-deploy-file` ran without the requested
     * key. Here the flag is always present when `key != null`.
     */
    fun buildMvnCommand(
        repo: Repo,
        artifactFile: String,
        pomFile: String,
        sourcesFile: String,
        javadocFile: String,
        key: String?,
        verbose: Boolean,
        settingsFile: String,
        snapshotVersion: Boolean,
    ): List<String> = buildList {
        add("mvn")
        add(mvnGoal(key != null))
        if (repo != Repo.FakeLocalRepo) {
            add("-gs")
            add(settingsFile)
        }
        if (verbose) add("--debug")
        add("-Dfile=$artifactFile")
        add("-DpomFile=$pomFile")
        add("-Dsources=$sourcesFile")
        add("-DrepositoryId=${repo.id}")
        add("-Durl=${repo.url}")
        if (!snapshotVersion) add("-Djavadoc=$javadocFile")
        if (key != null) add("-Dgpg.keyname=$key")
    }

    /**
     * Extract a `VAR = "value"` string variable from Starlark / pythonic source text (e.g.
     * `LIBRARY_VERSION` in versions.bzl). Comments after the value are dropped.
     */
    fun extractStringVariable(text: String, variable: String): String =
        text.lines()
            .first { it.startsWith(variable) }
            .substringBefore("#") // ditch comments
            .substringAfter("=")
            .trim('"', ' ')

    /**
     * From merged command output, the first trimmed line ending with `suffix` — used to pick a
     * built artifact's path out of bazel's build output.
     */
    fun firstLineEndingWith(output: String, suffix: String): String =
        output.lines().first { it.endsWith(suffix) }.trim()
}
