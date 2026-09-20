# Release Instructions

> Note: Versions should be semantic versioning maj.min.patch style, except for the default
> HEAD-SNAPSHOT which is the version used on the `main` branch.

This publishes through the [Central Portal](https://central.sonatype.com), which replaced
OSSRH/`oss.sonatype.org` (retired 2025-06-30). `scripts/deploy.main.kts` signs the 4 primary
artifacts (pom, jar, sources, javadoc), bundles them, uploads to the Portal, and polls until the
deployment reaches `VALIDATED`. It does **not** publish — that's a deliberate manual step below.

Preconditions:

  1. A [central.sonatype.com](https://central.sonatype.com) account with the `com.geekinasuit`
     namespace verified (this is a one-time setup already done for this namespace).
  2. A Central Portal user token: central.sonatype.com → account → Generate User Token. Export it
     as environment variables — **never** pass credentials on the command line, they'd be visible
     to anything reading `ps`:
     ```
     export CI_DEPLOY_USERNAME=<token username>
     export CI_DEPLOY_PASSWORD=<token password>
     ```
  3. A PGP private key (typically via GnuPG) whose public key you've uploaded to the keyservers.
     - Follow https://central.sonatype.org/publish/requirements/gpg/
     - `gpg --list-keys` to see the keys available.
     - `scripts/deploy.main.kts` signs with `gpg --batch`, which fails fast rather than prompting
       if it can't reach a usable passphrase. Prime the gpg-agent by signing something
       interactively once beforehand, or use a key with no passphrase / an unlocked agent.
  4. gpg, bazelisk, java, kotlin, `jj`, and `gh` installed — the usual preconditions for working
     in this repo at all (see AGENTS.md §VCS), not release-specific.

Steps:

  1. Prepare the repo (this is a jj workspace, not plain git)
      1. `jj git fetch`
      2. `jj new main`
      3. Edit `versions.bzl`, updating `LIBRARY_VERSION` to the release version (e.g. `0.1.0`,
         no `v` prefix — the tag gets the `v` in "Finish the release" below).
      4. `jj commit -m "Prepare to release version <version>" versions.bzl`
      5. `jj bookmark set release-<version> -r @-`
      6. `bazel build //... && bazel test //...`

  2. Deploy the artifacts
      1. With `CI_DEPLOY_USERNAME`/`CI_DEPLOY_PASSWORD` exported (step 2 of Preconditions):
         ```
         scripts/deploy.main.kts --key <yourgpgkey> --branch release-<version>
         ```
         `--branch` is **mandatory** here — branch auto-detection shells out to
         `git branch --show-current`, which fails in this jj workspace (no `.git` at the
         worktree root) and silently falls back to a local fake sink instead of erroring, so an
         omitted `--branch` looks like a successful release and deploys nowhere.
      2. gpg signs the 4 primary artifacts, the script bundles and uploads them, then polls the
         Portal every 10s (5 minute timeout). On success it prints the deployment id and a
         Publishing Manager URL.
      3. If it fails or times out, it prints how to drop the (still-`VALIDATED` or stuck)
         deployment — see Problems and Solutions below.

  3. Publish (manual, on purpose)
      1. Open central.sonatype.com → Publishing → Deployments (the script prints the deployments
         list URL, not a link to this specific deployment — find it by id or by being the only
         one pending) and inspect the contents.
      2. Hit "Publish". This is irreversible: "Once a component has been released and published
         to the Central Repository, it cannot be altered" — if you find a problem after
         publishing, the fix is a new version, not a correction to this one.
         (https://central.sonatype.org/faq/can-i-change-a-component/,
         https://central.sonatype.org/publish/publish-portal-guide/)
      3. Sonatype doesn't document how long sync to https://repo1.maven.org/maven2/ takes after
         publishing — give it some time and check there (or search.maven.org) rather than
         assuming a fixed window.

  4. Finish the release
      1. `jj git push -b release-<version>`
      2. Write release notes to a file, then:
         ```
         gh release create v<version> --target release-<version> --title v<version> --notes-file <notes-file>
         ```
         This creates the tag and the GitHub release in one step. `main` stays on
         `HEAD-SNAPSHOT`; the release branch is not merged back.
          - For minor releases, the notes should list the incremental change log since the last
            relevant release and link to the README.
          - For major releases, the notes should list the full feature set and key differences and
            link to the README and changelog.
      3. Update `README.md` (badge, coordinates, version) to reflect the newly published version.

  5. Announce (wherever — X, blogs, etc.)

Problems and Solutions:
  * gpg asks for a passphrase and `--batch` fails instead of prompting.
      - `gpg --batch` will not open an interactive prompt. Sign something else with the same key
        first to prime the gpg-agent's cache, then re-run the deploy.
      - If you still hit a "bad tty" style error from a different gpg invocation, prefix the
        command with `GPG_TTY=$(tty) `.
  * `scripts/deploy.main.kts` fails because of a missing pom or other artifact file.
      - Make sure you ran `bazel build //...` so all the files are built.
  * The deployment reaches a `FAILED` state, or the poll times out before reaching `VALIDATED`.
      - The script prints the Portal's reported errors (on `FAILED`) or the last observed state
        (on timeout), plus instructions to drop the deployment from the Publishing Manager so you
        can fix the problem and re-run. Re-running `scripts/deploy.main.kts` is not currently
        idempotent against a stuck prior deployment — drop it first.

Note: this runbook describes the release path as implemented; it has not yet been exercised
end-to-end against the live Central Portal (no token has been minted for this namespace yet).
