#!/usr/bin/env bash
#
# Unpacks mynes-desktop/target/mynes-*.zip somewhere temporary and runs the emulator out of it, the way somebody
# who downloaded it from the releases page would. Run it after `mvn -B package -DskipTests`. Both
# workflows call this rather than spelling it out, so there is one copy of the check to keep right.
#
# What it is really watching for is a jar that has lost something. A fat jar missing a resource
# builds without complaint and starts without complaint, and the palettes are read through a static
# initialiser that logs a warning and carries on when one will not load, so nothing anywhere fails
# loudly. Counting them is the only thing that notices.
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Committed, so this needs no network and no ROM of anybody's own. nestest draws its menu unprompted,
# which most cartridges do not: Super Mario Bros. and Tetris would sit on a title screen here and
# pass just as well without ever having run a frame of anything.
ROM="$ROOT_DIR/mynes-core/src/test/resources/nestest/nestest.nes"
FRAMES=60

# Palettes.NESDEV is compiled in; the other eleven are read out of /palettes inside the jar.
EXPECTED_PALETTES=12

WORK_DIR="$(mktemp -d)"

trap 'rm -rf "$WORK_DIR"' EXIT

shopt -s nullglob
zips=("$ROOT_DIR"/mynes-desktop/target/mynes-*.zip)
shopt -u nullglob

if [ ${#zips[@]} -ne 1 ]; then
    echo "expected one mynes-desktop/target/mynes-*.zip and found ${#zips[@]}; run mvn package first." >&2
    exit 1
fi

zip="${zips[0]}"
unzip -q "$zip" -d "$WORK_DIR"

# The zip is named after the directory inside it, which is what makes this exact rather than a glob.
dist="$WORK_DIR/$(basename "$zip" .zip)"
launcher="$dist/mynes"

if [ ! -x "$launcher" ]; then
    echo "$launcher is not executable: the zip has lost its file modes." >&2
    exit 1
fi

palettes="$("$launcher" --headless --list-palettes | wc -l | tr -d ' ')"

if [ "$palettes" -ne "$EXPECTED_PALETTES" ]; then
    echo "expected $EXPECTED_PALETTES palettes and got $palettes:" >&2
    "$launcher" --headless --list-palettes >&2
    exit 1
fi

# Then a cartridge, through the launcher, out of the unpacked tree. --expect-not-blank is the whole
# assertion: it exits 4 when the PPU drew one flat colour, which is what a machine that never
# started looks like. --out keeps the artifacts in the temporary directory, since the default is
# under target/ and belongs to a checkout rather than to this.
"$launcher" --headless \
    --rom "$ROM" \
    --frames "$FRAMES" \
    --out "$WORK_DIR/run" \
    --expect-not-blank \
    --quiet

echo "$(basename "$zip") is sound: $palettes palettes, and $FRAMES frames of nestest with a picture."
