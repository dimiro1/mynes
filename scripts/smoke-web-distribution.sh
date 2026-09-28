#!/usr/bin/env bash
# Verify the downloaded web ZIP itself, then run its Wasm bundle with a committed test ROM.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

shopt -s nullglob
zips=("$ROOT_DIR"/mynes-web/target/mynes-web-*.zip)
shopt -u nullglob
if [ "${#zips[@]}" -ne 1 ]; then
    echo "expected one mynes-web/target/mynes-web-*.zip; run mvn package first." >&2
    exit 1
fi

zip="${zips[0]}"
unzip -tq "$zip"
unzip -q "$zip" -d "$WORK_DIR"

for file in index.html app.js style.css LICENSE THIRD-PARTY.md LICENSE-TEAVM.txt \
        teavm/classes.wasm teavm/classes.wasm-runtime.js; do
    if [ ! -s "$WORK_DIR/$file" ]; then
        echo "web ZIP is missing $file" >&2
        exit 1
    fi
done

if unzip -Z1 "$zip" | grep -Eq '(^WEB-INF/|^META-INF/|\.jar$|\.teadbg$)'; then
    echo "web ZIP contains Java or debug build files" >&2
    exit 1
fi

node - "$WORK_DIR" "$ROOT_DIR/mynes-core/src/test/resources/accuracycoin/AccuracyCoin.nes" "$(basename "$zip")" <<'JS'
const fs = require("node:fs");
const path = require("node:path");
const crypto = require("node:crypto");
const site = process.argv[2];
const romPath = process.argv[3];
const zipName = process.argv[4];

require(path.join(site, "teavm/classes.wasm-runtime.js"));

(async () => {
    const vm = await TeaVM.wasmGC.load(path.join(site, "teavm/classes.wasm"));
    const rom = fs.readFileSync(romPath);
    const digest = crypto.createHash("sha256").update(rom).digest("hex");
    vm.exports.main([]);
    const region = vm.exports.load(rom, "AccuracyCoin.nes", digest);
    if (region !== "NTSC") throw new Error(`unexpected region: ${region}`);

    let colours = 0;
    let samples = 0;
    for (let i = 0; i < 120; i++) {
        const pixels = vm.exports.frame(0);
        if (pixels.length !== 256 * 240) throw new Error("wrong framebuffer size");
        colours = Math.max(colours, new Set(pixels).size);
        samples += vm.exports.audio().length;
    }
    if (colours < 2 || samples < 1000) {
        throw new Error(`no picture or sound: ${colours} colours, ${samples} samples`);
    }
    console.log(`${zipName}: 120 Wasm frames, ${colours} colours, ${samples} audio samples`);
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
JS
