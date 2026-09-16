/**
 * DeployCommand.kts — pure decision + assembly logic for the deploy script.
 *
 * Extracted from deploy.main.kts so the parts worth testing are unit-testable without shelling
 * out to bazel/mvn/gpg or touching the network. The main script keeps the process orchestration
 * (bazel build, mvn deploy for snapshots, and — for releases — GPG signing, checksumming, zipping,
 * and the Central Portal HTTP upload); everything here is a pure function of its arguments.
 *
 * Import with @file:Import("lib/DeployCommand.kts"). The importing script must supply the moshi
 * dependency on its classpath (@file:Import does not carry @file:DependsOn), i.e.
 * @file:DependsOn("com.squareup.moshi:moshi:1.15.1") in both deploy.main.kts and
 * deploy.test.main.kts.
 */

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64

object DeployCommand {
    // ---- Deployment target model -------------------------------------------------------------

    /**
     * How a Central Portal deployment is published after it uploads and validates.
     *
     * `USER_MANAGED` (the Portal default) uploads + validates, then STOPS and waits for a human to
     * publish (immutable once published). `AUTOMATIC` validates and publishes with no human step.
     * `wire` is the exact query-parameter value the Publisher API expects.
     */
    enum class PublishingType(val wire: String) {
        USER_MANAGED("USER_MANAGED"),
        AUTOMATIC("AUTOMATIC"),
    }

    /**
     * A deployment target. The two kinds are genuinely asymmetric:
     *   - `MvnRepo` is a plain Maven repository URL reached with `mvn deploy-file` (snapshots + the
     *     local fake sink used for dry runs).
     *   - `CentralPortalRelease` is the Sonatype Central Portal Publisher API, which takes a signed,
     *     checksummed zip bundle over HTTP — not an `mvn` repository URL at all.
     */
    sealed class Target {
        sealed class MvnRepo(val id: String, val url: String) : Target() {
            /**
             * Central Portal snapshots. Replaces the retired OSSRH snapshots endpoint
             * (oss.sonatype.org, EOL 2025-06-30). `id` must match the <server><id> in
             * tools/release/settings.xml so mvn finds the credentials.
             */
            object SonatypeSnapshots : MvnRepo(
                "central-snapshots",
                "https://central.sonatype.com/repository/maven-snapshots/"
            )

            /** A throwaway local sink so a dry run exercises the whole pipeline without publishing. */
            object FakeLocalRepo : MvnRepo(
                "local-fake",
                "file:///tmp/fakerepo"
            )

            override fun toString(): String = this::class.simpleName ?: "MvnRepo"
        }

        /** Release via the Central Portal Publisher API bundle upload. */
        data class CentralPortalRelease(val publishingType: PublishingType) : Target()
    }

    /** Outcome of target selection: proceed with a target, or stop with a message. */
    sealed class TargetDecision {
        data class Deploy(val target: Target) : TargetDecision()
        /** A non-error stop (surfaced by the caller as clikt PrintMessage) — e.g. CI off main. */
        data class Abort(val message: String) : TargetDecision()
        /** A user/config error (surfaced by the caller as clikt UsageError). */
        data class Usage(val message: String) : TargetDecision()
    }

    /**
     * Decide the deployment target from CI/branch/key/version state.
     *
     * `branch` is trimmed before comparison: it usually comes from `git branch --show-current`,
     * whose stdout carries a trailing newline, so an untrimmed `"main\n"` would never match "main".
     *
     * `publishingType` is carried into a release target so the first geekinasuit release can upload
     * under USER_MANAGED (inspect + publish by hand) while later releases can opt into AUTOMATIC.
     */
    fun selectTarget(
        ci: Boolean,
        branch: String,
        hasKey: Boolean,
        snapshotVersion: Boolean,
        version: String,
        publishingType: PublishingType = PublishingType.USER_MANAGED,
    ): TargetDecision {
        val b = branch.trim()
        return when {
            ci ->
                if (b == "main") TargetDecision.Deploy(Target.MvnRepo.SonatypeSnapshots)
                else TargetDecision.Abort("Aborting deployment on a non-main branch.")
            b.startsWith("release-") -> when {
                !hasKey -> TargetDecision.Usage("Must supply --key <gpgkey> for release deployments.")
                snapshotVersion -> TargetDecision.Usage(
                    "Don't use a snapshot version ($version) on a release branch ($b)"
                )
                else -> TargetDecision.Deploy(Target.CentralPortalRelease(publishingType))
            }
            else -> TargetDecision.Deploy(Target.MvnRepo.FakeLocalRepo)
        }
    }

    // ---- mvn command assembly (snapshot + fake paths) ----------------------------------------

    /** The mvn goal: a signed deploy when a GPG key is supplied, a plain deploy otherwise. */
    fun mvnGoal(hasKey: Boolean): String =
        if (hasKey) "gpg:sign-and-deploy-file" else "deploy:deploy-file"

    /**
     * Assemble the mvn invocation as an argv list, for the snapshot / fake-local paths.
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
        repo: Target.MvnRepo,
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
        if (repo != Target.MvnRepo.FakeLocalRepo) {
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

    // ---- Central Portal Publisher API: auth, endpoints, bundle layout ------------------------

    /** Base path for the Central Portal Publisher API. */
    const val CENTRAL_PUBLISHER_BASE = "https://central.sonatype.com/api/v1/publisher"

    /**
     * The `Authorization` header value for the Publisher API: `Bearer base64(tokenUser:tokenPass)`.
     * The token is a user-token pair generated at central.sonatype.com/usertoken. Building the
     * header here (rather than handing the token to a child process) keeps it out of any argv.
     */
    fun bearerHeader(tokenUser: String, tokenPass: String): String {
        val encoded = Base64.getEncoder()
            .encodeToString("$tokenUser:$tokenPass".toByteArray(Charsets.UTF_8))
        return "Bearer $encoded"
    }

    private fun query(value: String): String = URLEncoder.encode(value, "UTF-8")

    /**
     * The bundle-upload endpoint. `publishingType` selects USER_MANAGED vs AUTOMATIC; `name` is an
     * optional human-facing deployment name shown in the Portal.
     */
    fun uploadUrl(publishingType: PublishingType, name: String? = null): String {
        val params = buildList {
            add("publishingType=${query(publishingType.wire)}")
            if (name != null) add("name=${query(name)}")
        }
        return "$CENTRAL_PUBLISHER_BASE/upload?${params.joinToString("&")}"
    }

    /** The status-poll endpoint; the deployment id is passed as a query parameter, not a body. */
    fun statusUrl(deploymentId: String): String =
        "$CENTRAL_PUBLISHER_BASE/status?id=${query(deploymentId)}"

    /**
     * The deployment endpoint used both to publish a VALIDATED deployment (POST) and to drop one
     * (DELETE). The id is a server-issued UUID (URL-safe), so it is placed in the path verbatim.
     */
    fun deploymentUrl(deploymentId: String): String =
        "$CENTRAL_PUBLISHER_BASE/deployment/$deploymentId"

    /** A primary artifact the bundle carries: a (classifier, extension) pair. */
    data class Primary(val classifier: String?, val extension: String)

    /**
     * The four primary artifacts Central expects for a JVM library, in upload order. Central requires
     * a javadoc + sources jar; a placeholder javadoc jar is accepted for the javadoc slot.
     */
    val CENTRAL_PRIMARIES: List<Primary> = listOf(
        Primary(null, "pom"),
        Primary(null, "jar"),
        Primary("sources", "jar"),
        Primary("javadoc", "jar"),
    )

    /** Maven group id → repository path segment (dots become slashes). */
    fun groupPath(groupId: String): String = groupId.replace('.', '/')

    /** The canonical Maven filename for a coordinate: `<artifactId>-<version>[-<classifier>].<ext>`. */
    fun mavenFileName(artifactId: String, version: String, classifier: String?, extension: String): String {
        val c = if (classifier != null) "-$classifier" else ""
        return "$artifactId-$version$c.$extension"
    }

    /** The bundle-relative directory a coordinate's files live under (Maven repository layout). */
    fun bundleDir(groupId: String, artifactId: String, version: String): String =
        "${groupPath(groupId)}/$artifactId/$version"

    /**
     * One primary artifact's placement in the bundle: its canonical Maven filename, its full path
     * within the zip, and the sibling files Central requires alongside it.
     *
     * The siblings are exactly `.asc` (GPG signature) + `.md5` + `.sha1`. Central's requirement is
     * NON-recursive: the checksum files are not themselves signed, and the `.asc` files are not
     * themselves checksummed (verbatim: "`.asc` files don't need checksum files, nor do checksum
     * files need `.asc` signature files"). `.sha256`/`.sha512` are optional and deliberately skipped.
     */
    data class BundleEntry(
        val classifier: String?,
        val extension: String,
        val fileName: String,
        val bundlePath: String,
        val siblings: List<String>,
    )

    /** The full set of bundle placements for a coordinate — one [BundleEntry] per primary artifact. */
    fun bundleEntries(groupId: String, artifactId: String, version: String): List<BundleEntry> {
        val dir = bundleDir(groupId, artifactId, version)
        return CENTRAL_PRIMARIES.map { p ->
            val name = mavenFileName(artifactId, version, p.classifier, p.extension)
            val path = "$dir/$name"
            BundleEntry(
                classifier = p.classifier,
                extension = p.extension,
                fileName = name,
                bundlePath = path,
                siblings = listOf("$path.asc", "$path.md5", "$path.sha1"),
            )
        }
    }

    // ---- Central Portal Publisher API: status polling ----------------------------------------

    /**
     * Deployment lifecycle states from the Publisher API `deploymentState` field. (The webhook
     * payload uses a different field, `status`, with a reduced set — do not confuse the two; this
     * enum is for the /status poll response.)
     */
    enum class DeploymentState { PENDING, VALIDATING, VALIDATED, PUBLISHING, PUBLISHED, FAILED }

    /**
     * The parsed shape of a /status response. `rawState` preserves the server's exact string even
     * when it is not a state this tool recognizes (so an unknown value is surfaced, not swallowed);
     * `state` is the enum parse, or null when missing/unrecognized. `errors` is a human-readable
     * rendering of the `errors` field present on FAILED (its exact JSON shape is not documented).
     */
    data class StatusResponse(
        val state: DeploymentState?,
        val rawState: String?,
        val errors: String?,
    )

    private val statusAdapter by lazy {
        Moshi.Builder().build().adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
        )
    }

    /**
     * Parse a /status response body. A malformed or partial body yields an all-null [StatusResponse]
     * rather than throwing: a poller must not die on one bad read — an overall timeout in the caller
     * bounds how long an unparseable stream is tolerated.
     */
    fun parseStatusResponse(json: String): StatusResponse {
        val map = try {
            statusAdapter.fromJson(json)
        } catch (e: Exception) {
            null
        } ?: return StatusResponse(null, null, null)
        val rawState = map["deploymentState"] as? String
        val state = rawState?.let { runCatching { DeploymentState.valueOf(it) }.getOrNull() }
        val errors = map["errors"]?.toString()
        return StatusResponse(state, rawState, errors)
    }

    /** What the poller should do with a polled state, given the publishing type. */
    enum class PollDisposition { WAIT, SUCCESS, FAILED }

    /**
     * The state at which an upload has succeeded for a given publishing type. For USER_MANAGED that
     * is VALIDATED — the deployment then waits for a manual publish and NEVER reaches PUBLISHED on
     * its own, so a poller that waits for PUBLISHED here would hang until timeout on a healthy
     * upload. For AUTOMATIC it is PUBLISHED.
     */
    fun terminalSuccessState(publishingType: PublishingType): DeploymentState =
        when (publishingType) {
            PublishingType.USER_MANAGED -> DeploymentState.VALIDATED
            PublishingType.AUTOMATIC -> DeploymentState.PUBLISHED
        }

    /**
     * Classify a polled state into keep-waiting / done / failed, honoring the USER_MANAGED vs
     * AUTOMATIC asymmetry above. A null (unparseable/missing) state is treated as WAIT so a single
     * bad read doesn't abort the poll; the caller's timeout bounds it.
     */
    fun classifyPoll(state: DeploymentState?, publishingType: PublishingType): PollDisposition =
        when (state) {
            null -> PollDisposition.WAIT
            DeploymentState.FAILED -> PollDisposition.FAILED
            DeploymentState.PENDING, DeploymentState.VALIDATING -> PollDisposition.WAIT
            DeploymentState.VALIDATED -> when (publishingType) {
                // Terminal for user-managed; for automatic it will progress to PUBLISHING/PUBLISHED.
                PublishingType.USER_MANAGED -> PollDisposition.SUCCESS
                PublishingType.AUTOMATIC -> PollDisposition.WAIT
            }
            DeploymentState.PUBLISHING -> when (publishingType) {
                // Past validation already; from this tool's point of view the upload is done.
                PublishingType.USER_MANAGED -> PollDisposition.SUCCESS
                PublishingType.AUTOMATIC -> PollDisposition.WAIT
            }
            DeploymentState.PUBLISHED -> PollDisposition.SUCCESS
        }

    // ---- Signing / checksum helpers ----------------------------------------------------------

    /** Lower-case hex encoding of a byte array. (Mask to 8 bits so a negative Byte isn't sign-extended.) */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    /** The `.md5` companion content for a file's bytes: the hex-encoded MD5 digest. */
    fun md5Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("MD5").digest(bytes))

    /** The `.sha1` companion content for a file's bytes: the hex-encoded SHA-1 digest. */
    fun sha1Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-1").digest(bytes))

    // ---- versions.bzl parsing + bazel output filtering ---------------------------------------

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
