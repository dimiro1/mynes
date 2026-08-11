#!/usr/bin/env bash
#
# Fetches the palettes MyNES draws with into src/main/resources/palettes.
#
# Source:  https://www.firebrandx.com/nespalette.html (the standalone zips) and
#          https://www.firebrandx.com/downloads/Novemeber-2017-Palettes.zip (the bundle --
#          the misspelt "Novemeber" is the real path, not a typo here), plus the NESdev
#          wiki for the one PAL palette.
# License: firebrandx.com states no terms of any kind: no licence, no copyright notice and
#          no request for credit, on either page or in the bundled descriptions. The same
#          files ship with Mesen, Nestopia UE and puNES on that footing. The NESdev wiki is
#          public domain. Credits are recorded in the PROVENANCE file this script writes.
#
# Each NTSC palette is a flat 192 byte file: 64 RGB triplets, no emphasis variants. The PAL
# one is 1536, having been generated with the emphasis variants spelled out. They are
# committed, so this only has to be re-run to prove where they came from or to pick up a
# revision upstream.
#
# Idempotent: re-running fetches everything again and lands byte for byte identical files.
#
set -euo pipefail

BASE_URL="https://www.firebrandx.com"
EXPECTED_BYTES=192

# The only palette here for the PAL PPU, and the only one not from firebrandx.com, which
# has none. Generated with Pally from the 2C07's own signal and hosted on the NESdev wiki;
# 1536 bytes because it spells out all eight emphasis variants rather than leaving them to
# be approximated. The sha1 is recorded rather than enforced, for the same reason the NTSC
# files are only length checked: an upstream revision should be picked up, not refused.
PAL_ID="2c07"
PAL_URL="https://www.nesdev.org/w/images/default/6/69/2C07_wiki.pal"
PAL_PAGE="https://www.nesdev.org/wiki/File:2C07_wiki.pal"
PAL_BYTES=1536
PAL_SHA1="3955a5f2685e94bda1a539e39fd98d468fe0e56e"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT_DIR/src/main/resources/palettes"
WORK_DIR="$(mktemp -d)"

trap 'rm -rf "$WORK_DIR"' EXIT

command -v unzip >/dev/null || { echo "unzip is required." >&2; exit 1; }

# id | zip, relative to $BASE_URL | the .pal name inside that zip.
#
# The bundle carries older copies of composite-direct, nes-classic and pvm-style-d93; the
# standalone zips win, since those are the ones the site still links as current.
PALETTES=(
    "composite-direct|/graphics/nespalettes/nespalette-composite-direct.zip|Composite Direct (FBX).pal"
    "digital-prime|/graphics/nespalettes/digitalprime.zip|Digital Prime (FBX).pal"
    "magnum|/graphics/nespalettes/magnum.zip|Magnum (FBX).pal"
    "nes-classic|/graphics/nespalettes/nespalette-nes-classic-fbx.zip|NES Classic (FBX).pal"
    "pvm-style-d93|/graphics/nespalettes/nespalette-pvm-style-d93.zip|PVM Style D93 (FBX).pal"
    "smooth|/downloads/Novemeber-2017-Palettes.zip|Smooth (FBX).pal"
    "smooth-v2|/graphics/nespalettes/smoothv2.zip|Smooth V2 (FBX).pal"
    "pc-10|/downloads/Novemeber-2017-Palettes.zip|PC-10.pal"
    "sony-cxa|/downloads/Novemeber-2017-Palettes.zip|Sony CXA.pal"
    "wavebeam|/downloads/Novemeber-2017-Palettes.zip|Wavebeam.pal"
)

# Downloads a zip once however many palettes come out of it, and unpacks it into a
# directory of its own so that two bundles sharing a filename could not collide.
fetch() {
    local path="$1"
    local name unpacked

    name="$(basename "$path" .zip)"
    unpacked="$WORK_DIR/$name"

    if [ -d "$unpacked" ]; then
        echo "$unpacked"
        return
    fi

    curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
        -o "$WORK_DIR/$name.zip" "$BASE_URL$path"
    unzip -o -q "$WORK_DIR/$name.zip" -d "$unpacked"

    echo "$unpacked"
}

mkdir -p "$DEST_DIR"

count=0
for entry in "${PALETTES[@]}"; do
    IFS='|' read -r id path member <<< "$entry"

    unpacked="$(fetch "$path")"
    source="$unpacked/$member"

    if [ ! -f "$source" ]; then
        echo "$member is not in $path -- aborting." >&2
        exit 1
    fi

    # 192 bytes or the file is not what this expects, and NESPalette.fromRGB would refuse
    # it at startup rather than here.
    bytes="$(wc -c < "$source" | tr -d ' ')"
    if [ "$bytes" -ne "$EXPECTED_BYTES" ]; then
        echo "$member is $bytes bytes, expected $EXPECTED_BYTES -- aborting." >&2
        exit 1
    fi

    cp "$source" "$DEST_DIR/$id.pal"
    count=$((count + 1))
done

curl -fsSL --retry 5 --retry-delay 2 --retry-all-errors \
    -o "$WORK_DIR/$PAL_ID.pal" "$PAL_URL"

bytes="$(wc -c < "$WORK_DIR/$PAL_ID.pal" | tr -d ' ')"
if [ "$bytes" -ne "$PAL_BYTES" ]; then
    echo "$PAL_ID.pal is $bytes bytes, expected $PAL_BYTES -- aborting." >&2
    exit 1
fi

cp "$WORK_DIR/$PAL_ID.pal" "$DEST_DIR/$PAL_ID.pal"
count=$((count + 1))

cat > "$WORK_DIR/PROVENANCE" <<EOF
The palettes MyNES draws with: ten NTSC ones from firebrandx.com, and one PAL one from
the NESdev wiki.

Source:      $BASE_URL/nespalette.html
             $PAL_PAGE
Fetched:     $(date -u +%Y-%m-%d)
Files:       $count palettes. The NTSC ones are 64 RGB triplets each with no emphasis
             variants; the PAL one is all 512 entries, emphasis included.
Regenerate:  scripts/download-palettes.sh

License:     None stated for the NTSC ten. firebrandx.com publishes them with no licence,
             no copyright notice and no request for credit, in either page or the
             descriptions bundled with them. Mesen, Nestopia UE and puNES ship them on the
             same footing. The NESdev wiki is public domain. Credits are recorded below
             because they are owed, not required.

Credits:     The (FBX) palettes are FirebrandX's work.
             Wavebeam is Nakedarthur's.
             PC-10 is read off the PlayChoice-10 arcade PPU.
             Sony CXA approximates certain consumer Sony sets; its author is unknown.
             2c07 was generated with Pally v0.22.1 from the 2C07's own signal, and is the
             palette table on the NESdev wiki's PPU palettes page.

The NESdev palette MyNES defaults to on NTSC is not here: it is a constant in
Palettes.java, so that the list is never empty and the default never depends on a resource
being present. The PAL default is 2c07, which is, so a build that lost it falls back to
NESdev and looks wrong rather than refusing to start.

Downloaded from:
EOF

for entry in "${PALETTES[@]}"; do
    IFS='|' read -r id path member <<< "$entry"
    printf '  %-21s %s (%s)\n' "$id.pal" "$BASE_URL$path" "$member" >> "$WORK_DIR/PROVENANCE"
done

printf '  %-21s %s\n' "$PAL_ID.pal" "$PAL_URL" >> "$WORK_DIR/PROVENANCE"
printf '  %-21s sha1 %s\n' "" "$PAL_SHA1" >> "$WORK_DIR/PROVENANCE"

# "Fetched" is the date this content was fetched, not the date the script last ran, so a
# re-run that turns up the same bytes keeps the old date and leaves the tree untouched.
if [ -f "$DEST_DIR/PROVENANCE" ] \
    && diff -q -I '^Fetched:' "$DEST_DIR/PROVENANCE" "$WORK_DIR/PROVENANCE" >/dev/null; then
    echo "Wrote $count palettes to $DEST_DIR (unchanged)"
else
    cp "$WORK_DIR/PROVENANCE" "$DEST_DIR/PROVENANCE"
    echo "Wrote $count palettes to $DEST_DIR"
fi
