"""Offline tests for stage_screenshots.py: per-platform sources, cleanup, order and caps."""
from __future__ import annotations

import io
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import stage_screenshots as ss  # noqa: E402


def png(width: int, height: int, marker: str) -> bytes:
    # Signature + IHDR length/type + size: enough for read_png_size without Pillow.
    return (
        b"\x89PNG\r\n\x1a\n\x00\x00\x00\x0dIHDR"
        + width.to_bytes(4, "big")
        + height.to_bytes(4, "big")
        + marker.encode()
    )


class StageTests(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.ios_src = self.root / "store" / "screenshots" / "ios"
        self.play_src = self.root / "store" / "screenshots" / "play"
        self.ios_dest = self.root / "store" / "metadata" / "screenshots" / "ios" / "6.7"
        self.play_dest = self.root / "store" / "metadata" / "screenshots" / "play" / "phone"
        for patch in (
            mock.patch.object(ss, "ROOT", self.root),
            mock.patch.object(ss, "IOS_SOURCE", self.ios_src),
            mock.patch.object(ss, "PLAY_SOURCE", self.play_src),
            mock.patch.object(ss, "IOS_67_DIR", self.ios_dest),
            mock.patch.object(ss, "PLAY_PHONE_DIR", self.play_dest),
            # Raw copies, so staged bytes can be compared with the sources.
            mock.patch.object(ss, "try_import_pillow", return_value=None),
            mock.patch.dict(os.environ, {"STORE_UPLOAD_SCREENSHOTS": ""}),
        ):
            patch.start()
            self.addCleanup(patch.stop)

    def write(self, directory: Path, *names: str) -> None:
        directory.mkdir(parents=True, exist_ok=True)
        for name in names:
            (directory / name).write_bytes(png(1290, 2796, f"{directory.name}/{name}"))

    def stage(self, **kwargs):
        with redirect_stdout(io.StringIO()):
            return ss.stage(**kwargs)

    def names(self, directory: Path) -> list[str]:
        return sorted(p.name for p in directory.glob("*.png"))

    def test_all_stages_each_store_from_its_own_source_in_name_order(self):
        self.write(self.ios_src, "02-b.png", "10-j.png", "01-a.png", "home.png")
        self.write(self.play_src, "02-y.png", "01-x.png")

        copied = self.stage(dry_run=False, platform="all")

        self.assertEqual(self.names(self.ios_dest), ["01-a.png", "02-b.png", "10-j.png"])
        self.assertEqual(self.names(self.play_dest), ["01-x.png", "02-y.png"])
        self.assertEqual(
            [p.name for p in copied],
            ["01-x.png", "02-y.png", "01-a.png", "02-b.png", "10-j.png"],
        )
        for dest, src in ((self.ios_dest, self.ios_src), (self.play_dest, self.play_src)):
            for name in self.names(dest):
                self.assertEqual((dest / name).read_bytes(), (src / name).read_bytes())

    def test_ios_only_replaces_ios_set_and_keeps_staged_play_files(self):
        self.write(self.ios_src, "01-a.png")
        self.write(self.ios_dest, "09-stale.png")
        self.write(self.play_dest, "01-staged.png")

        self.stage(dry_run=False, platform="ios")

        self.assertEqual(self.names(self.ios_dest), ["01-a.png"])
        self.assertEqual(self.names(self.play_dest), ["01-staged.png"])

    def test_play_only_replaces_play_set_and_keeps_staged_ios_files(self):
        self.write(self.play_src, "01-x.png")
        self.write(self.play_dest, "09-stale.png")
        self.write(self.ios_dest, "01-staged.png")

        self.stage(dry_run=False, platform="play")

        self.assertEqual(self.names(self.play_dest), ["01-x.png"])
        self.assertEqual(self.names(self.ios_dest), ["01-staged.png"])

    def test_one_platform_does_not_need_the_other_source(self):
        self.write(self.ios_src, "01-a.png")

        self.stage(dry_run=False, platform="ios")

        self.assertEqual(self.names(self.ios_dest), ["01-a.png"])
        self.assertFalse(self.play_dest.exists())

    def test_play_stages_at_most_the_phone_cap(self):
        names = [f"{i:02d}-p.png" for i in range(1, ss.PLAY_PHONE_MAX + 2)]
        self.write(self.play_src, *names)

        self.stage(dry_run=False, platform="play")

        self.assertEqual(self.names(self.play_dest), names[: ss.PLAY_PHONE_MAX])

    def test_dry_run_writes_and_removes_nothing(self):
        self.write(self.ios_src, "01-a.png")
        self.write(self.play_src, "01-x.png")
        self.write(self.ios_dest, "09-stale.png")

        copied = self.stage(dry_run=True, platform="all")

        self.assertEqual(copied, [])
        self.assertEqual(self.names(self.ios_dest), ["09-stale.png"])
        self.assertFalse(self.play_dest.exists())

    def test_missing_or_empty_source_fails_before_touching_staged_files(self):
        self.write(self.ios_dest, "01-staged.png")
        for setup in (lambda: None, lambda: self.write(self.ios_src, "home.png")):
            setup()
            with self.subTest(source_files=self.names(self.ios_src) if self.ios_src.exists() else None):
                with self.assertRaises(SystemExit), redirect_stderr(io.StringIO()):
                    self.stage(dry_run=False, platform="ios")
                self.assertEqual(self.names(self.ios_dest), ["01-staged.png"])


if __name__ == "__main__":
    unittest.main()
