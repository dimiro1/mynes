# MyNES

A Work in Progress Nes emulator.

## What is done?

- Cycle stepped CPU;
- Dot accurate NTSC PPU: background and sprite pipelines, loopy scroll registers, sprite 0 hit,
  the sprite overflow bug, open bus decay and colour emphasis, into a 256x240 ARGB framebuffer;
- Game window: File > Open a `.nes` file and it runs, at 60.0988 frames a second, with the
  overscan cropped and the picture scaled to the window;
- CHR debug window;
- Most of blargg tests are passing;
- nestests is passing;
- Per-opcode verification against the Tom Harte single step tests;

Mappers 0 (NROM) and 3 (CNROM) are supported. Nothing is wired to the controllers yet, so games
run but cannot be played. There is no APU, no PAL timing and no save states, and three PPU
details are still missing: the $2006 delayed VRAM address, the write-ignore window while the PPU
warms up, and OAM decay.

## Tests

```sh
mvn test
```

That runs everything, including the [Tom Harte SingleStepTests](https://github.com/SingleStepTests/65x02)
for all 256 opcodes against a committed subset of 500 cases per opcode. Each opcode is checked
twice: once for its end state and cycle count, and once for its exact per-cycle bus traffic.

### PPU test ROMs

The PPU is verified against blargg's test ROMs, vendored under `src/test/resources` together with
the readme that came with each suite: `ppu-vbl-nmi` (VBlank and NMI timing to a single PPU clock),
`ppu-sprite-hit`, `ppu-sprite-overflow`, `oam` (`oam_read` and `oam_stress`), `ppu-open-bus`,
`ppu-read-buffer` and `ppu-tests-2005` (palette RAM, VRAM access, sprite RAM, VBlank clear time).
All of them pass, with no accepted deviations.

They report in two different ways, both handled by `BlarggRunner`: the later ROMs use the $6000
status protocol, and the 2005 era ones write a numeric result code into zero page. A failure code
means nothing on its own -- look it up in the `readme.txt` vendored next to the ROM.

For the full 10,000 cases per opcode, fetch the upstream set once:

```sh
./scripts/download-6502-tests.sh   # ~1.4GB into the gitignored testdata/
mvn test                          # picks the full set up automatically
```

To run only the per-cycle bus trace stage, or to skip it:

```sh
mvn test -Dgroups=bus-trace
mvn test -DexcludedGroups=bus-trace
```