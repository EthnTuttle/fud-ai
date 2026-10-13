# Store release automation

GitHub Actions plumbing for App Store + Play Store releases, including listing
copy, What's New, screenshots, and the IAP / subscription / tip / credit catalog
(RevenueCat).

**Nothing reaches a store unless its switch is on.** An `ios-v*` tag runs
[`ios-release.yml`](../.github/workflows/ios-release.yml): GitHub Actions builds,
signs and uploads the iOS binary (Xcode Cloud no longer builds Fud AI). An
`android-v*` tag builds the Android app and makes a GitHub Release;
`STORE_PRODUCTION_ROLLOUT` (off by default) publishes only production, live,
with no draft.

## iOS release flow (`ios-v*` tag)

```
ios-vX.Y tag ─► quality checks ─► resolve switches (tag must equal MARKETING_VERSION)
  └─ IOS_RELEASE_AUTOMATION off ─► GitHub Release only
  └─ on:
       IOS_UPLOAD_BUILD ─► next build number from App Store Connect
                        ─► archive Release ─► verify app id/version/build in every bundle
                        ─► cloud-signed export + upload
                        ─► wait until THIS build is VALID (poll 30 s, up to 120 min from upload start)
       IOS_UPLOAD_LISTING / IOS_UPLOAD_WHATS_NEW / IOS_UPLOAD_SCREENSHOTS ─► version metadata
       IOS_SUBMIT_REVIEW ─► select this run's build, read it back, set release type
                         ─► recheck build + selection ─► submit version and IAPs/subscriptions
  └─ GitHub Release (only if every enabled step succeeded)
```

A manual **Run workflow** (`workflow_dispatch`) is a validation run: it archives,
signs, exports and validates the IPA with App Store Connect, and never uploads,
edits the listing, submits or creates a GitHub Release.

Submission fails instead of continuing when the build times out, fails
processing, is expired, is ambiguous, belongs to another app/version, was
uploaded before this run, or is not the build selected on the version. An older
build is never substituted.

**Xcode Cloud:** its tag-triggered "Release" workflow was removed in App Store
Connect. Xcode Cloud triggers live in App Store Connect, not in this repo, so a
GitHub switch cannot stop them; never re-create an `ios-v*` Xcode Cloud workflow
or both pipelines will upload a build for the same tag.

## Switches

Repository **Variables** (Settings → Secrets and variables → Actions → Variables).
Missing means off. For iOS, any value other than `true/false/yes/no/on/off/1/0`
fails the run.

### iOS

| Variable | When `true` |
|----------|-------------|
| `IOS_RELEASE_AUTOMATION` | Master switch. When off, every iOS store operation below is skipped |
| `IOS_UPLOAD_BUILD` | Build, sign and upload the binary to App Store Connect, then wait for processing |
| `IOS_UPLOAD_LISTING` | Description, keywords, promotional text, marketing/support URLs |
| `IOS_UPLOAD_WHATS_NEW` | What's New text |
| `IOS_UPLOAD_SCREENSHOTS` | 6.7" screenshots (see below) |
| `IOS_SUBMIT_REVIEW` | Select this run's build and submit the version **and** Ready-to-Submit IAPs / subscriptions for review. Requires `IOS_UPLOAD_BUILD` |
| `IOS_PUBLIC_RELEASE` | Release automatically after approval. Off = manual release. Requires `IOS_SUBMIT_REVIEW` |
| `IOS_BUILD_POLL_SECONDS` | Poll interval, default 30 (10–600) |
| `IOS_BUILD_WAIT_MINUTES` | Total wait from upload start, default 120 (10–300) |

### Android

| Variable | When `true` |
|----------|-------------|
| `STORE_UPLOAD_WHATS_NEW` | Attach English What's New on the Play upload |
| `STORE_UPLOAD_LISTING` | Upload Play title/description |
| `STORE_UPLOAD_SCREENSHOTS` | Upload Play phone screenshots |
| `STORE_PRODUCTION_ROLLOUT` | Publish this Android build to Play production, live |

### Shared

| Variable | When `true` |
|----------|-------------|
| `STORE_SYNC_REVENUECAT` | **Fails the workflow** — RevenueCat write/sync is not implemented yet |

Copy names from [`gates.env.example`](gates.env.example).

## Secrets

Android: `ANDROID_KEYSTORE_*`, `PLAY_SERVICE_ACCOUNT_JSON`.

iOS:

| Secret | Used for |
|--------|----------|
| `APP_STORE_CONNECT_API_KEY_ID` | Admin-role ASC API key: build numbers, cloud-managed distribution signing, upload, build readiness, listing, review |
| `APP_STORE_CONNECT_ISSUER_ID` | ASC issuer |
| `APP_STORE_CONNECT_API_KEY_P8` | ASC private key (`.p8` body) |
| `IOS_DEV_SIGNING_P12_BASE64` | Dedicated CI Apple Development identity (base64 `.p12`; certificate "Apple Development: Created via API", ID `7H3T2H8USS`, expires 2027-10-13) that signs the archive. App Store signing happens at export with Apple's cloud-managed distribution certificate |
| `IOS_DEV_SIGNING_P12_PASSWORD` | Password for that `.p12` |

If that certificate expires or is revoked, create a new Apple Development
certificate for CI (App Store Connect API, `certificateType: DEVELOPMENT`), replace
both `IOS_DEV_SIGNING_*` secrets, and revoke the old one. Its private key exists
only in the GitHub secret.

Later for catalog automation:

| Secret | Used for |
|--------|----------|
| `REVENUECAT_SECRET_API_KEY` | Catalog sync / product verification |

## Scripts

| Script | Role |
|--------|------|
| [`scripts/store/prepare_listing_text.py`](../scripts/store/prepare_listing_text.py) | `APPSTORE.md` / `PLAYSTORE.md` → `store/metadata/` |
| [`scripts/store/prepare_whats_new.py`](../scripts/store/prepare_whats_new.py) | What's New for Play + iOS |
| [`scripts/store/stage_screenshots.py`](../scripts/store/stage_screenshots.py) | `web/assets/screenshots/*.png` → `store/metadata/screenshots/` |
| [`scripts/store/ios_release.py`](../scripts/store/ios_release.py) | iOS switches, build number, wait for this run's build |
| [`scripts/store/asc_release.py`](../scripts/store/asc_release.py) | ASC listing, What's New, 6.7" screenshots, build selection, submit for review |
| [`scripts/store/test_ios_release.py`](../scripts/store/test_ios_release.py) | Offline tests: disabled/invalid switches, timeouts, failures, mismatches |
| [`scripts/store/play_listing.py`](../scripts/store/play_listing.py) | Play listing text + phone screenshots |
| [`scripts/store/sync_revenuecat_catalog.py`](../scripts/store/sync_revenuecat_catalog.py) | Validate catalog; fail if sync gate on |

When any live iOS or Play listing gate is on, release workflows install
[`requirements-store.txt`](../requirements-store.txt) (PyJWT, Google API client).

Environment flags consumed by `asc_release.py` (set by `ios-release.yml` from the
resolved iOS switches): `UPLOAD_LISTING`, `UPLOAD_WHATS_NEW`, `UPLOAD_SCREENSHOTS`,
`SUBMIT_IOS_REVIEW`, `PUBLIC_RELEASE`. `play_listing.py` uses `UPLOAD_LISTING` and
`UPLOAD_SCREENSHOTS` from the Android switches.

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
(iOS) or `STORE_UPLOAD_SCREENSHOTS` (Android) is true, copying those PNGs into `store/metadata/screenshots/` until you replace
them with store-tailored exports.

## How to enable later

1. Keep `APPSTORE.md` / `PLAYSTORE.md` / `RELEASE_NOTES.md` in sync; run the prepare scripts locally if needed.
2. Confirm ASC + Play secrets are present.
3. Flip only the variables you want. iOS operations also need `IOS_RELEASE_AUTOMATION`.
4. Before relying on iOS signing changes, run **iOS Release → Run workflow**
   (validation only; nothing is uploaded).
5. Tag as usual (`ios-vX.Y` / `android-vX.Y`). Leave `STORE_PRODUCTION_ROLLOUT`,
   `IOS_SUBMIT_REVIEW` and `IOS_PUBLIC_RELEASE` off until you intend that release.

## Dry-run

Local:

```bash
python3 scripts/store/validate_catalog.py
python3 scripts/store/prepare_whats_new.py --platform all --tag android-v6.1 --out /tmp/whatsnew
python3 scripts/store/prepare_listing_text.py --platform all --out /tmp/listing
python3 scripts/store/stage_screenshots.py --dry-run
python3 -m unittest scripts/store/test_ios_release.py
UPLOAD_LISTING=true python3 scripts/store/asc_release.py --dry-run --metadata-dir store/metadata
UPLOAD_LISTING=true python3 scripts/store/play_listing.py --dry-run --metadata-dir store/metadata
STORE_SYNC_REVENUECAT=false python3 scripts/store/sync_revenuecat_catalog.py
```
