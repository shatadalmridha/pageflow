# PageFlow – Smooth Document Reader (v0.3.0)

Offline Android **reader** for DOCX (Bengali-first) and PDF. No internet permission; Translate and Wiktionary open outside the app.
See **FEATURES.md** (what exists, limits), **TESTING.md** (automated vs real-device tests), **CHANGELOG.md**.

## First build (no Android Studio needed)

### A. Put the project on GitHub
1. github.com → **New repository** → name `pageflow` → **Private** → Create.
2. **Add file → Upload files**, drag the *contents* of this ZIP (including the hidden `.github`
   folder) into the page → **Commit changes**. (On a phone, use the browser's desktop view.)
   Check that `.github/workflows/build.yml` is visible in the repo.

### B. Run the build
3. **Actions** tab → if asked, click *"I understand my workflows, enable them"*.
4. Click **Build PageFlow APK** → **Run workflow** (or it already started from your upload).
5. Wait 5–12 minutes. Green check = success, red cross = open the run, click the failed step,
   copy the red lines and send them to Claude.
6. Open the finished run → **Artifacts** → download **PageFlow-APKs** → unzip.
   Without signing secrets you get `app-debug.apk` only (that is expected).

### C. Install
7. Copy the APK to your phone, open it, allow "Install unknown apps" when asked.

### Signing (do this after the first green build)
Without it, every build is signed with a different temporary key and can't update the previous one.
8. **Actions → Create signing key (run once) → Run workflow**.
9. Open that run → Artifacts → download **PageFlow-signing-key-KEEP-PRIVATE** (kept 1 day) → unzip.
10. Repo → **Settings → Secrets and variables → Actions → New repository secret**. Create four:
    * `KEYSTORE_BASE64` = the whole text inside `keystore-base64.txt`
    * `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` = values from `SECRETS-READ-ME.txt`
11. **Keep a private backup of `pageflow-release.jks` and the passwords.** If you lose them you can
    never publish an update that installs over the old app.
12. Re-run **Build PageFlow APK**: you now get signed `app-release.apk` and `app-debug.apk`.
    Debug uses application id `com.pageflow.reader.debug`, so it installs beside the release app.
    Delete the keystore artifact/file from your downloads once the secrets are saved.

## Running tests locally (optional)
`gradle testDebugUnitTest` (Gradle 8.9, JDK 17). CI runs them on every build.

## Updating an existing repository with this version
1. In your repository open each changed folder/file and upload over it: use *Add file → Upload files* and drop the contents
   of this ZIP (keep folder structure: `app/`, `.github/`, root files). Same-named files are replaced.
2. Commit. The build starts by itself. If the new workflow files do not appear under `.github/workflows/`, create them with
   *Add file → Create new file* and paste the contents.
3. No new Secrets are needed; your existing signing Secrets keep working.
