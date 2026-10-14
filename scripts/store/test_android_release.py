"""Offline tests for android_release.py: switches, artifact checks, Play read-back."""
from __future__ import annotations

import base64
import hashlib
import io
import os
import sys
import tempfile
import unittest
import zipfile
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import android_release as ar  # noqa: E402
import prepare_whats_new  # noqa: E402

ALL_ON = {name: "true" for name, _ in ar.OPERATION_SWITCHES}


def gates(env=None, *, event="push", ref="android-v7.2", version="7.2"):
    return ar.resolve_gates(env or {}, event=event, ref_name=ref, version_name=version)


def pem(der: bytes) -> str:
    body = base64.b64encode(der).decode()
    return f"-----BEGIN CERTIFICATE-----\n{body}\n-----END CERTIFICATE-----\n"


UPLOAD_DER = b"upload-certificate"
UPLOAD = hashlib.sha256(UPLOAD_DER).hexdigest()
DEBUG = hashlib.sha256(b"debug-certificate").hexdigest()
IDENTITY = {"package": ar.PACKAGE_NAME, "version_code": 42, "version_name": "7.2"}


class SwitchTests(unittest.TestCase):
    def test_missing_settings_mean_nothing_runs(self):
        result = gates()
        self.assertFalse(result["build"])
        self.assertFalse(result["github_release"])
        self.assertFalse(result["play_upload"])
        self.assertEqual(result["play_tracks"], "")
        self.assertTrue(any("is off" in note for note in result["notes"]))

    def test_master_off_ignores_every_operation(self):
        result = gates(ALL_ON)
        for _, key in ar.OPERATION_SWITCHES:
            self.assertFalse(result[key], key)
        self.assertFalse(result["play_upload"])
        self.assertTrue(any("ANDROID_PRODUCTION_ROLLOUT" in note for note in result["notes"]))

    def test_master_alone_builds_and_releases_on_github_only(self):
        result = gates({ar.MASTER: "true"})
        self.assertTrue(result["build"])
        self.assertTrue(result["github_release"])
        self.assertFalse(result["play_upload"])
        self.assertFalse(result["listing"])

    def test_invalid_values_fail_even_when_master_is_off(self):
        for name, _ in ar.OPERATION_SWITCHES:
            with self.subTest(name=name), self.assertRaises(ar.ReleaseError):
                gates({name: "maybe"})
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "enabled"})

    def test_switch_values_are_case_and_space_insensitive(self):
        result = gates({ar.MASTER: " YES ", "ANDROID_INTERNAL_TESTING": "On"})
        self.assertEqual(result["play_tracks"], "internal")
        result = gates({ar.MASTER: "1", "ANDROID_INTERNAL_TESTING": "0"})
        self.assertFalse(result["play_upload"])

    def test_each_track_switch_maps_to_its_play_track(self):
        for name, track in (
            ("ANDROID_INTERNAL_TESTING", "internal"),
            ("ANDROID_CLOSED_TESTING", "alpha"),
            ("ANDROID_PRODUCTION_ROLLOUT", "production"),
        ):
            with self.subTest(name=name):
                result = gates({ar.MASTER: "true", name: "true"})
                self.assertTrue(result["play_upload"])
                self.assertEqual(result["play_tracks"], track)

    def test_testing_tracks_never_imply_production(self):
        result = gates(
            {ar.MASTER: "true", "ANDROID_INTERNAL_TESTING": "true", "ANDROID_CLOSED_TESTING": "true"}
        )
        self.assertEqual(result["play_tracks"], "internal,alpha")
        self.assertFalse(result["production"])

    def test_production_output_decides_latest_github_release(self):
        # The workflow marks the GitHub Release Latest only when `production` is true;
        # otherwise it is a pre-release so the iOS release stays Latest.
        for env, expected in (
            ({ar.MASTER: "true"}, "false"),
            ({ar.MASTER: "true", "ANDROID_INTERNAL_TESTING": "true"}, "false"),
            ({ar.MASTER: "true", "ANDROID_PRODUCTION_ROLLOUT": "true"}, "true"),
            ({"ANDROID_PRODUCTION_ROLLOUT": "true"}, "false"),
        ):
            with self.subTest(env=env), tempfile.NamedTemporaryFile("r", suffix=".out") as out, \
                    mock.patch.dict(os.environ, {"GITHUB_OUTPUT": out.name}), redirect_stdout(io.StringIO()):
                resolved = gates(env)
                resolved.pop("notes")
                ar.write_outputs(resolved)
                self.assertIn(f"production={expected}\n", Path(out.name).read_text())

    def test_all_tracks_go_in_one_upload(self):
        result = gates({ar.MASTER: "true", **ALL_ON})
        self.assertEqual(result["play_tracks"], "internal,alpha,production")
        self.assertTrue(result["whats_new"])

    def test_listing_and_screenshots_need_no_track(self):
        result = gates(
            {ar.MASTER: "true", "ANDROID_UPLOAD_LISTING": "true", "ANDROID_UPLOAD_SCREENSHOTS": "true"}
        )
        self.assertTrue(result["listing"])
        self.assertTrue(result["screenshots"])
        self.assertFalse(result["play_upload"])

    def test_whats_new_without_a_track_is_skipped_with_a_note(self):
        result = gates({ar.MASTER: "true", "ANDROID_UPLOAD_WHATS_NEW": "true"})
        self.assertFalse(result["whats_new"])
        self.assertTrue(any("What's New" in note for note in result["notes"]))

    def test_tag_must_match_version_name(self):
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, ref="android-v7.1")
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, ref="ios-v7.2")
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, ref="android-v7.2.0")

    def test_manual_run_only_validates(self):
        result = gates({ar.MASTER: "true", **ALL_ON}, event="workflow_dispatch", ref="main")
        self.assertEqual(result["mode"], "validate")
        self.assertTrue(result["build"])
        self.assertFalse(result["github_release"])
        self.assertFalse(result["play_upload"])
        for _, key in ar.OPERATION_SWITCHES:
            self.assertFalse(result[key], key)

    def test_manual_run_still_rejects_invalid_values(self):
        with self.assertRaises(ar.ReleaseError):
            gates({"ANDROID_CLOSED_TESTING": "sure"}, event="workflow_dispatch", ref="main")

    def test_unsupported_event_fails(self):
        with self.assertRaises(ar.ReleaseError):
            gates({ar.MASTER: "true"}, event="pull_request")


class GradleVersionTests(unittest.TestCase):
    def test_reads_default_config_and_ignores_suffixes(self):
        text = """
            versionCode = 42
            versionName = "7.2"
            debug { versionNameSuffix = "-debug" }
        """
        self.assertEqual(ar.gradle_version(text), ("7.2", 42))

    def test_ambiguous_or_missing_version_fails(self):
        with self.assertRaises(ar.ReleaseError):
            ar.gradle_version('versionCode = 1\nversionName = "1.0"\nversionName = "1.1"\n')
        with self.assertRaises(ar.ReleaseError):
            ar.gradle_version('versionName = "1.0"\n')

    def test_repository_gradle_file_parses(self):
        name, code = ar.gradle_version(ar.GRADLE.read_text(encoding="utf-8"))
        self.assertRegex(name, r"^[0-9]+(\.[0-9]+){1,2}$")
        self.assertGreater(code, 0)


class ArtifactTests(unittest.TestCase):
    def test_pem_fingerprints_hash_the_der_bytes(self):
        text = "Signer #1:\n" + pem(UPLOAD_DER) + "noise\n" + pem(b"debug-certificate")
        self.assertEqual(ar.pem_fingerprints(text), {UPLOAD, DEBUG})

    def test_apksigner_fingerprints(self):
        output = (
            "Verifies\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
            f"Signer #1 certificate DN: CN=Fud AI\nSigner #1 certificate SHA-256 digest: {UPLOAD.upper()}\n"
            "Signer #1 certificate SHA-1 digest: 0123\n"
        )
        self.assertEqual(ar.apksigner_fingerprints(output), {UPLOAD})

    def test_apksigner_fingerprints_newer_format_ignores_keys_and_stamps(self):
        output = (
            "Verifies\nNumber of signers: 1\n"
            f"V2 Signer: certificate SHA-256 digest: {UPLOAD}\n"
            f"V2 Signer: public key SHA-256 digest: {DEBUG}\n"
            f"Source Stamp Signer certificate SHA-256 digest: {DEBUG}\n"
        )
        self.assertEqual(ar.apksigner_fingerprints(output), {UPLOAD})

    def test_badging_identity(self):
        output = (
            "package: name='com.apoorvdarshan.calorietracker' versionCode='42' versionName='7.2' "
            "platformBuildVersionName='16'\nsdkVersion:'26'\n"
        )
        found = ar.badging_identity(output)
        self.assertEqual(found, (ar.PACKAGE_NAME, 42, "7.2"))
        ar.check_identity("APK", found, **IDENTITY)

    def test_badging_without_package_line_fails(self):
        with self.assertRaises(ar.ReleaseError):
            ar.badging_identity("sdkVersion:'26'\n")

    def test_manifest_identity(self):
        xml = (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.apoorvdarshan.calorietracker" android:versionCode="42" '
            'android:versionName="7.2"><application/></manifest>'
        )
        found = ar.manifest_identity(xml)
        self.assertEqual(found, (ar.PACKAGE_NAME, 42, "7.2"))

    def test_bad_manifest_fails(self):
        with self.assertRaises(ar.ReleaseError):
            ar.manifest_identity("not xml")
        with self.assertRaises(ar.ReleaseError):
            ar.manifest_identity('<manifest package="x"/>')

    def test_wrong_app_or_version_is_rejected(self):
        for found in (
            ("com.apoorvdarshan.calorietracker.debug", 42, "7.2"),
            (ar.PACKAGE_NAME, 41, "7.2"),
            (ar.PACKAGE_NAME, 42, "7.2-debug"),
        ):
            with self.subTest(found=found), self.assertRaises(ar.ReleaseError):
                ar.check_identity("AAB", found, **IDENTITY)

    def test_only_the_upload_key_may_sign(self):
        ar.check_signers("APK", {UPLOAD}, upload_cert=UPLOAD)
        for found in (set(), {DEBUG}, {UPLOAD, DEBUG}):
            with self.subTest(found=found), self.assertRaises(ar.ReleaseError):
                ar.check_signers("APK", found, upload_cert=UPLOAD)


class SignatureCoverageTests(unittest.TestCase):
    LONG = "base/root/" + "x" * 80 + ".properties"

    def bundle(self, entries, manifest_names):
        manifest = "Manifest-Version: 1.0\r\n\r\n"
        for name in manifest_names:
            line = f"Name: {name}"
            # JAR manifests wrap at 72 bytes with a leading-space continuation.
            wrapped = "\r\n ".join([line[:72]] + [line[i:i + 71] for i in range(72, len(line), 71)])
            manifest += f"{wrapped}\r\nSHA-256-Digest: AAAA\r\n\r\n"
        handle = tempfile.NamedTemporaryFile(suffix=".aab", delete=False)
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        with zipfile.ZipFile(handle.name, "w") as bundle:
            bundle.writestr("META-INF/MANIFEST.MF", manifest)
            bundle.writestr("META-INF/FUDAI.SF", "signature")
            bundle.writestr("META-INF/FUDAI.RSA", "block")
            bundle.writestr("base/", "")
            for name in entries:
                bundle.writestr(name, "data")
        return Path(handle.name)

    def test_fully_signed_bundle_passes(self):
        names = ["BundleConfig.pb", "base/manifest/AndroidManifest.xml", self.LONG]
        self.assertEqual(ar.unsigned_entries(self.bundle(names, names)), [])

    def test_added_unsigned_entry_is_reported(self):
        names = ["BundleConfig.pb", "base/dex/classes.dex"]
        found = ar.unsigned_entries(self.bundle(names + ["base/dex/classes2.dex"], names))
        self.assertEqual(found, ["base/dex/classes2.dex"])

    def test_bundle_without_manifest_is_entirely_unsigned(self):
        handle = tempfile.NamedTemporaryFile(suffix=".aab", delete=False)
        handle.close()
        self.addCleanup(Path(handle.name).unlink)
        with zipfile.ZipFile(handle.name, "w") as bundle:
            bundle.writestr("BundleConfig.pb", "data")
        self.assertEqual(ar.unsigned_entries(Path(handle.name)), ["BundleConfig.pb"])


class TrackReadBackTests(unittest.TestCase):
    def fetcher(self, tracks):
        return lambda name: tracks[name]

    def test_completed_release_on_every_track_passes(self):
        tracks = {
            "internal": {"releases": [{"versionCodes": ["42"], "status": "completed"}]},
            "alpha": {
                "releases": [
                    {"versionCodes": ["41"], "status": "completed"},
                    {"versionCodes": ["42"], "status": "completed"},
                ]
            },
        }
        messages = ar.check_tracks(self.fetcher(tracks), ["internal", "alpha"], 42)
        self.assertEqual(len(messages), 2)
        self.assertIn("internal testing", messages[0])
        self.assertIn("closed testing", messages[1])
        self.assertIn("review", messages[1])

    def test_missing_or_unfinished_release_fails(self):
        for release in (
            {},
            {"releases": [{"versionCodes": ["41"], "status": "completed"}]},
            {"releases": [{"versionCodes": ["42"], "status": "draft"}]},
            {"releases": [{"versionCodes": ["42"], "status": "halted"}]},
        ):
            with self.subTest(release=release), self.assertRaises(ar.ReleaseError):
                ar.check_tracks(self.fetcher({"production": release}), ["production"], 42)


class PlayWhatsNewTests(unittest.TestCase):
    """The strict check the gates job runs only when ANDROID_UPLOAD_WHATS_NEW is on."""

    def write(self, block):
        out = tempfile.TemporaryDirectory()
        self.addCleanup(out.cleanup)
        with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            try:
                prepare_whats_new.write_play_whatsnew(block, Path(out.name))
            except SystemExit as exc:
                return exc.code, Path(out.name)
        return 0, Path(out.name)

    def test_each_locale_becomes_a_whatsnew_file(self):
        code, out = self.write("<en-US>\nFixes.\n</en-US>\n<de-DE>\nFehler.\n</de-DE>\n")
        self.assertEqual(code, 0)
        self.assertEqual((out / "whatsnew-en-US").read_text(encoding="utf-8"), "Fixes.\n")
        self.assertTrue((out / "whatsnew-de-DE").is_file())

    def test_missing_empty_or_too_long_text_fails(self):
        for label, block in {
            "no locales": "Fixes.",
            "empty": "<en-US>\n\n</en-US>",
            "too long": "<en-US>\n" + "x" * 501 + "\n</en-US>",
        }.items():
            with self.subTest(label):
                code, _ = self.write(block)
                self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
