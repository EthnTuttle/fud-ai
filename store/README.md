# Store release automation

GitHub Actions plumbing for App Store + Play Store releases, including listing
copy, What's New, screenshots, and the IAP / subscription / tip / credit catalog
(RevenueCat).

**Nothing reaches a store unless its switch is on.** An `ios-v*` tag runs
[`ios-release.yml`](../.github/workflows/ios-release.yml): GitHub Actions builds,
signs and uploads the iOS binary (Xcode Cloud no longer builds Fud AI). An
`android-v*` tag runs [`android-release.yml`](../.github/workflows/android-release.yml):
GitHub Actions builds, verifies and signs the Android app, and uploads it to the
Play tracks you switch on.

## iOS release flow (`ios-v*` tag)

```
ios-vX.Y tag ─► quality checks ─► resolve switches (tag must equal MARKETING_VERSION)
  └─ IOS_RELEASE_AUTOMATION off ─► GitHub Release only
  └─ on:
       IOS_UPLOAD_WHATS_NEW ─► check APPSTORE.md What's New (before anything is built)
       IOS_UPLOAD_BUILD ─► next build number from App Store Connect
                        ─► archive Release ─► verify app id/version/build in every bundle
                        ─► cloud-signed export + upload
                        ─► wait-for-build job: until THIS build is VALID
                           (poll 30 s, up to 120 min from upload start)
       IOS_UPLOAD_LISTING / IOS_UPLOAD_WHATS_NEW / IOS_UPLOAD_SCREENSHOTS ─► version metadata
       IOS_SUBMIT_REVIEW ─► select this run's build, read it back, set release type
                         ─► recheck build + selection ─► submit version and IAPs/subscriptions
  └─ GitHub Release, marked Latest (only if every enabled step succeeded)
```

Waiting for the build is its own `wait-for-build` job, which outputs the
processed build's ID to the `app-store-connect` job. If a listing, screenshot or
review step fails, **Re-run failed jobs** reuses that build ID instead of
waiting again; a second wait would always time out, because the wait budget
counts from the original upload.

A manual **Run workflow** (`workflow_dispatch`) is a validation run: it archives,
signs, exports and validates the IPA with App Store Connect, and never uploads,
edits the listing, submits or creates a GitHub Release. Apple refuses versions it
has already approved, so before bumping the project version, fill in the optional
**marketing_version** input with the next version (e.g. `7.2`); the build is
validated as that version without changing the project.

Submission fails instead of continuing when the build times out, fails
processing, is expired, is ambiguous, belongs to another app/version, was
uploaded before this run, or is not the build selected on the version. An older
build is never substituted.

## Android release flow (`android-v*` tag)

```
android-vX.Y tag ─► resolve switches (tag must equal versionName)
  └─ ANDROID_RELEASE_AUTOMATION off ─► nothing is built or released
  └─ on:
       ANDROID_UPLOAD_WHATS_NEW (with a track) ─► check PLAYSTORE.md What's New
       quality checks ─► check the keystore secrets are set
                      ─► build signed AAB + APK
                      ─► verify package, versionName/versionCode, upload-key signature
                      ─► Sigstore signatures + build provenance
       ANDROID_UPLOAD_LISTING / ANDROID_UPLOAD_SCREENSHOTS ─► Play listing
       GitHub Release (AAB + APK): Latest with ANDROID_PRODUCTION_ROLLOUT,
                                   otherwise a pre-release (iOS stays Latest)
       ANDROID_INTERNAL_TESTING / ANDROID_CLOSED_TESTING / ANDROID_PRODUCTION_ROLLOUT
                      ─► one Play upload to every selected track (+ What's New if on)
                      ─► read each track back: this versionCode, status completed
```

A manual **Run workflow** (`workflow_dispatch`) is a validation run: it builds
the signed AAB and APK and verifies them, and never uploads, signs with Sigstore
or creates a GitHub Release. Both validation and tag runs stop with the names of
any missing `ANDROID_KEYSTORE_*` / `ANDROID_KEY_*` secrets before Gradle runs.

Each track switch is independent: testing tracks never imply production, and
production never falls back to a testing track. A completed Play release can
still go through Google's review before testers or users get it.

**Xcode Cloud:** its tag-triggered "Release" workflow was removed in App Store
Connect. Xcode Cloud triggers live in App Store Connect, not in this repo, so a
GitHub switch cannot stop them; never re-create an `ios-v*` Xcode Cloud workflow
or both pipelines will upload a build for the same tag.

## Release notes and What's New

The tag's `## ios-vX.Y` / `## android-vX.Y` section in `RELEASE_NOTES.md` becomes
the GitHub Release body. A tag without a section still gets a GitHub Release,
with GitHub's generated notes; an empty section fails the run.

Store What's New comes from the What's New block in `APPSTORE.md` (iOS) and
`PLAYSTORE.md` (Play), not from `RELEASE_NOTES.md`. It is read only when
`IOS_UPLOAD_WHATS_NEW` / `ANDROID_UPLOAD_WHATS_NEW` is on, and then checked before
anything is built: iOS needs non-empty text of at most 4,000 characters; Play
needs `<locale>` blocks of at most 500 characters each, including `<en-US>`
(the only one uploaded). With the switch off, missing or unfit What's New text
never blocks a release.

## Concurrency

Tag runs of each workflow share one concurrency group with `queue: max`, so
releases run one at a time and every waiting tag run stays queued (by default a
third run would cancel the pending one). Manual validation runs each get their
own group and never wait for, or cancel, a release run.

## Switches

Repository **Variables** (Settings → Secrets and variables → Actions → Variables).
Missing means off. Any value other than `true/false/yes/no/on/off/1/0` fails the
run, even when the platform's master switch is off.

### iOS

| Variable | When `true` |
|----------|-------------|
| `IOS_RELEASE_AUTOMATION` | Master switch. When off, every iOS store operation below is skipped |
| `IOS_UPLOAD_BUILD` | Build, sign and upload the binary to App Store Connect, then wait for processing |
| `IOS_UPLOAD_LISTING` | Description, keywords, promotional text, marketing/support URLs |
| `IOS_UPLOAD_WHATS_NEW` | What's New text from `APPSTORE.md`, checked before the build. Off: never read |
| `IOS_UPLOAD_SCREENSHOTS` | 6.7" screenshots (see below) |
| `IOS_SUBMIT_REVIEW` | Select this run's build and submit the version **and** Ready-to-Submit IAPs / subscriptions for review. Requires `IOS_UPLOAD_BUILD` |
| `IOS_PUBLIC_RELEASE` | Release automatically after approval. Off = manual release. Requires `IOS_SUBMIT_REVIEW` |
| `IOS_BUILD_POLL_SECONDS` | Poll interval, default 30 (10–600) |
| `IOS_BUILD_WAIT_MINUTES` | Total wait from upload start, default 120 (10–300) |

### Android

| Variable | When `true` |
|----------|-------------|
| `ANDROID_RELEASE_AUTOMATION` | Master switch. On: the tag builds and verifies the signed AAB/APK and creates the GitHub Release. Off: the tag builds and releases nothing, and every switch below is ignored |
| `ANDROID_UPLOAD_LISTING` | Play title, short and full description |
| `ANDROID_UPLOAD_WHATS_NEW` | English What's New from `PLAYSTORE.md` on this run's Play release, checked before the build. Needs a track below; skipped with a note otherwise. Off: never read |
| `ANDROID_UPLOAD_SCREENSHOTS` | Play phone screenshots (see below) |
| `ANDROID_INTERNAL_TESTING` | Upload this build to Play **internal testing** |
| `ANDROID_CLOSED_TESTING` | Upload this build to Play **closed testing** (the `alpha` track) |
| `ANDROID_PRODUCTION_ROLLOUT` | Upload this build to Play **production**, live, and mark the GitHub Release Latest. Off: the `android-v*` GitHub Release is a pre-release, so the iOS release stays Latest |

### Shared

| Variable | When `true` |
|----------|-------------|
| `STORE_SYNC_REVENUECAT` | **Fails the workflow** — RevenueCat write/sync is not implemented yet |

Copy names from [`gates.env.example`](gates.env.example).

## Secrets

Android:

| Secret | Used for |
|--------|----------|
| `ANDROID_KEYSTORE_BASE64` | base64 of `fudai-release.jks`, the upload keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Upload key alias (`fudai`) |
| `ANDROID_KEY_PASSWORD` | Upload key password |
| `PLAY_SERVICE_ACCOUNT_JSON` | Play publisher service-account JSON key (listing, uploads, track read-back) |

The four keystore secrets are required by every Android run, validation included.

iOS:

| Secret | Used for |
|--------|----------|
| `APP_STORE_CONNECT_API_KEY_ID` | Admin-role ASC API key: build numbers, cloud-managed distribution signing, upload, build readiness, listing, review |
| `APP_STORE_CONNECT_ISSUER_ID` | ASC issuer |
| `APP_STORE_CONNECT_API_KEY_P8` | ASC private key (`.p8` body) |
| `IOS_DEV_SIGNING_P12_BASE64` | CI Apple Development identity (base64 `.p12`; certificate "Apple Development: Created via API", ID `NVNGDQGCKK`, expires 2027-10-14) that signs the archive. App Store signing happens at export with Apple's cloud-managed distribution certificate |
| `IOS_DEV_SIGNING_P12_PASSWORD` | Password for that `.p12` |

The same CI identity signs the archives of Verceltics, Abysm, iitjee, Quit All,
Scowld and Sesslet: Apple allows one API-created development certificate per API
key. Its private key exists only in those repositories' `IOS_DEV_SIGNING_*`
secrets. When it expires or is revoked, revoke it first (App Store Connect API,
`DELETE /v1/certificates/<id>`), create a new one (`certificateType: DEVELOPMENT`),
and replace both `IOS_DEV_SIGNING_*` secrets in Fud AI and in every repository
listed above.

To give another app repository this identity, don't rotate it. Copy both
`IOS_DEV_SIGNING_*` secrets on GitHub's side, so the private key never leaves
GitHub:

1. Read the target repository's Actions public key:
   `gh api repos/<owner>/<repo>/actions/secrets/public-key` (`key` and `key_id`).
2. On a temporary branch of a private repository that already has the secrets
   and whose workflows don't run on other branches, push the workflow below with
   `<key>` filled in. It prints only the sealed ciphertext, which only GitHub can
   decrypt for the target repository.
3. Store each sealed value in the target (`<run id>` is the finished temporary
   run). This stops on the first error and refuses anything but exactly two
   sealed values:

   ```sh
   (
     set -euo pipefail
     sealed=$(gh run view <run id> -R <owner>/<source repo> --log \
       | grep -oE 'SEALED [A-Z0-9_]+ [A-Za-z0-9+/=]+')
     [ "$(printf '%s\n' "$sealed" | grep -c '^SEALED ')" -eq 2 ] \
       || { echo "expected 2 sealed values" >&2; exit 1; }
     while read -r _ name value; do
       gh api -X PUT "repos/<owner>/<repo>/actions/secrets/$name" \
         -f encrypted_value="$value" -f key_id="<key_id>" --silent
     done <<< "$sealed"
   )
   ```

4. Once `gh secret list -R <owner>/<repo>` shows both `IOS_DEV_SIGNING_*`
   secrets with today's date, delete the temporary branch and its run
   (`gh api -X DELETE repos/<owner>/<source repo>/git/refs/heads/<branch>`,
   `gh run delete <run id> -R <owner>/<source repo>`).

Then add the three `APP_STORE_CONNECT_*` secrets, add the repository to the list
above, and run a validation dispatch.

The temporary workflow (`.github/workflows/tmp-seal-ci-signing.yml`):

```yaml
name: Seal CI signing secrets (temporary)
on:
  push:
    branches: [tmp/seal-ci-signing]
permissions: {}
jobs:
  seal:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - name: Seal with the target repository's Actions public key
        env:
          IOS_DEV_SIGNING_P12_BASE64: ${{ secrets.IOS_DEV_SIGNING_P12_BASE64 }}
          IOS_DEV_SIGNING_P12_PASSWORD: ${{ secrets.IOS_DEV_SIGNING_P12_PASSWORD }}
          TARGET_PUBLIC_KEY: "<key>"
        run: |
          python3 -m venv "$RUNNER_TEMP/venv"
          "$RUNNER_TEMP/venv/bin/pip" install --quiet pynacl==1.5.0
          "$RUNNER_TEMP/venv/bin/python" - <<'PY'
          import base64, os
          from nacl import encoding, public
          key = public.PublicKey(os.environ["TARGET_PUBLIC_KEY"].encode(), encoding.Base64Encoder())
          box = public.SealedBox(key)
          for name in ("IOS_DEV_SIGNING_P12_BASE64", "IOS_DEV_SIGNING_P12_PASSWORD"):
              value = os.environ[name]
              if not value:
                  raise SystemExit(f"missing {name}")
              sealed = base64.b64encode(box.encrypt(value.encode())).decode()
              print(f"SEALED {name} {sealed}")
          PY
```

Later for catalog automation:

| Secret | Used for |
|--------|----------|
| `REVENUECAT_SECRET_API_KEY` | Catalog sync / product verification |

## Scripts

| Script | Role |
|--------|------|
| [`scripts/store/prepare_listing_text.py`](../scripts/store/prepare_listing_text.py) | `APPSTORE.md` / `PLAYSTORE.md` → `store/metadata/` |
| [`scripts/store/prepare_whats_new.py`](../scripts/store/prepare_whats_new.py) | What's New for Play + iOS (`APPSTORE.md` / `PLAYSTORE.md`), with the store length checks |
| [`scripts/release_notes.py`](../scripts/release_notes.py) | The tag's `RELEASE_NOTES.md` section for the GitHub Release; `--if-present` prints nothing when there is none |
| [`scripts/store/stage_screenshots.py`](../scripts/store/stage_screenshots.py) | `web/assets/screenshots/*.png` → `store/metadata/screenshots/` |
| [`scripts/store/ios_release.py`](../scripts/store/ios_release.py) | iOS switches, build number, wait for this run's build |
| [`scripts/store/asc_release.py`](../scripts/store/asc_release.py) | ASC listing, What's New, 6.7" screenshots, build selection, submit for review |
| [`scripts/store/test_ios_release.py`](../scripts/store/test_ios_release.py) | Offline tests: disabled/invalid switches, timeouts, failures, mismatches, optional release notes, iOS What's New checks |
| [`scripts/store/android_release.py`](../scripts/store/android_release.py) | Android switches, AAB/APK identity + signature checks, Play track read-back |
| [`scripts/store/test_android_release.py`](../scripts/store/test_android_release.py) | Offline tests: disabled/invalid switches, track selection, wrong app/version/key, Play What's New checks |
| [`scripts/store/play_listing.py`](../scripts/store/play_listing.py) | Play listing text + phone screenshots |
| [`scripts/store/sync_revenuecat_catalog.py`](../scripts/store/sync_revenuecat_catalog.py) | Validate catalog; fail if sync gate on |

When any live iOS or Play gate is on, release workflows install
[`requirements-store.txt`](../requirements-store.txt) (PyJWT, Google API client).

Environment flags consumed by `asc_release.py` (set by `ios-release.yml` from the
resolved iOS switches): `UPLOAD_LISTING`, `UPLOAD_WHATS_NEW`, `UPLOAD_SCREENSHOTS`,
`SUBMIT_IOS_REVIEW`, `PUBLIC_RELEASE`. `play_listing.py` uses `UPLOAD_LISTING` and
`UPLOAD_SCREENSHOTS`, set by `android-release.yml` from the resolved Android switches.

### iOS subscription submission caveat

`IOS_SUBMIT_REVIEW` submits the App Store version and every
`READY_TO_SUBMIT` (or `DEVELOPER_REJECTED`) subscription in one step. As of the
2026 App Store Connect workflow, subscriptions attach to the review submission
through the `subscriptionVersion` relationship on Review Submission Items (the
`subscription` relationship is rejected by the API), so a first-time
subscription must ship with the version. Subscriptions that are still
`READY_TO_SUBMIT` attach to the current in-flight version; consumable and tip
IAPs go through the dedicated `inAppPurchaseSubmissions` endpoint.

A subscription whose in-flight version was **removed** from an earlier
submission sits in `DEVELOPER_REJECTED`. Attaching that version to the new
submission clears it, so no manual step is required; only products listed in
`store/catalog/products.json` are submitted, and the script stops before
submitting the version (non-zero exit) if a required product cannot be added.

If the app version is already `WAITING_FOR_REVIEW`, a product attached to a
newly created draft is submitted on its own so it still reaches review. A retry
reuses the same draft but skips anything already attached, and the catch-up path
refuses to submit a draft that contains an app version, so a rerun can never
duplicate products or ship unrelated items.

## Canonical catalog

- [`catalog/products.json`](catalog/products.json) — App Store + Play product IDs (subs, credits, tips)
- [`catalog/revenuecat.json`](catalog/revenuecat.json) — entitlements + offerings

Source of truth for product IDs stays aligned with [`docs/HOSTED_AI_REVENUECAT.md`](../docs/HOSTED_AI_REVENUECAT.md).

## Metadata layout

```
store/metadata/
  play/en-US/          # title, short/full description, whatsnew (generated)
  ios/en-US/           # description, keywords, promotional_text, whats_new
  screenshots/
    play/phone/        # Play phoneScreenshots (staged from web assets)
    ios/6.7/           # ASC APP_IPHONE_67 slot
```

Marketing PNGs live in [`web/assets/screenshots/`](../web/assets/screenshots/).
Release workflows run `stage_screenshots.py` when `IOS_UPLOAD_SCREENSHOTS`
(iOS) or `ANDROID_UPLOAD_SCREENSHOTS` (Android) is true, copying those PNGs into `store/metadata/screenshots/` until you replace
them with store-tailored exports.

## How to enable later

1. Keep `APPSTORE.md` / `PLAYSTORE.md` / `RELEASE_NOTES.md` in sync; run the prepare scripts locally if needed.
2. Confirm ASC + Play secrets are present.
3. Flip only the variables you want. iOS operations also need `IOS_RELEASE_AUTOMATION`;
   Android ones need `ANDROID_RELEASE_AUTOMATION`.
4. Before relying on signing changes, run **iOS Release → Run workflow** or
   **Android Release → Run workflow** (validation only; nothing is uploaded).
5. Tag as usual (`ios-vX.Y` / `android-vX.Y`). Try a build on
   `ANDROID_INTERNAL_TESTING` first; leave `ANDROID_PRODUCTION_ROLLOUT`,
   `IOS_SUBMIT_REVIEW` and `IOS_PUBLIC_RELEASE` off until you intend that release.

## Dry-run

Local:

```bash
python3 scripts/store/validate_catalog.py
python3 scripts/store/prepare_whats_new.py --platform all --tag android-v6.1 --out /tmp/whatsnew
python3 scripts/store/prepare_listing_text.py --platform all --out /tmp/listing
python3 scripts/store/stage_screenshots.py --dry-run
python3 -m unittest scripts/store/test_ios_release.py
python3 -m unittest scripts/store/test_android_release.py
UPLOAD_LISTING=true python3 scripts/store/asc_release.py --dry-run --metadata-dir store/metadata
UPLOAD_LISTING=true python3 scripts/store/play_listing.py --dry-run --metadata-dir store/metadata
STORE_SYNC_REVENUECAT=false python3 scripts/store/sync_revenuecat_catalog.py
```
