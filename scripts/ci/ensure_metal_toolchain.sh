#!/bin/bash
# Xcode distributes Metal separately; the voice orb shader needs its compiler.
set -euo pipefail

# Finding a tool path is insufficient when Xcode only has the downloader shim.
if xcrun metal --version >/dev/null 2>&1; then
  echo 'Metal compiler is available.'
else
  echo 'Installing the Metal toolchain for the selected Xcode.'
  xcodebuild -downloadComponent MetalToolchain
fi

# Fail before the expensive build if installation did not provide a usable tool.
xcrun metal --version
