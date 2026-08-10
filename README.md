# MyNES

A Work in Progress Nes emulator.

## What is done?

- Cycle stepped CPU;
- Dot accurate NTSC PPU: background and sprite pipelines, loopy scroll registers, sprite 0 hit,
  the sprite overflow bug, the delayed $2006 VRAM address, the write-ignore window the chip holds
  over $2000, $2001, $2005 and $2006 while it warms up, open bus decay, OAM decay and colour
  emphasis, into a 256x240 framebuffer of colour indices;
- Game window: File > Open a `.nes` file and it runs, at 60.0988 frames a second, with the
  overscan cropped and the picture scaled to the window;
- Eleven NTSC palettes: the NESdev set plus the ten measured ones from firebrandx.com, among them
  NES Classic, PVM Style D93, Smooth and Wavebeam. Settings > Palette... lists them next to a
  swatch grid and applies each one as the selection moves, so they can be compared against the
  running game -- or against a paused frame;
- Machine menu: Reset (the console button, memory survives), Power Cycle, and Pause;
- Keyboard control of player one, remappable;
- CHR debug window: every tile of a bank with a zoomed preview, coloured with any of the eight
  palettes the game is running with, live -- CHR RAM rewrites and palette changes show up as
  they happen;
- Debug toggles to hide the background or sprite layer without the game noticing;
- Most of blargg tests are passing;
- nestests is passing;
- Per-opcode verification against the Tom Harte single step tests;

Mappers 0 (NROM) and 3 (CNROM) are supported. There is no second player, no APU, no PAL timing
and no save states.

## Controls

| NES button | Key |
|---|---|
| D-pad | Arrow keys |
| A | X |
| B | Z |
| Start | Enter |
| Select | Shift |

Settings > Controller... remaps any of them: click a button, press the key you want on it. The
change applies at once, no save button, and lands in `~/.mynes/config.properties` along with the
chosen palette:

```properties
video.palette=nesdev
controller1.a=VK_X
controller1.left=VK_LEFT
```

That file can be edited by hand instead. Binding values are the names of the `VK_` constants in
`java.awt.event.KeyEvent`, an empty value leaves the button unbound, and an entry that is missing
or misspelled -- a key name or a palette id -- falls back to its default and says so in the log.

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
All of them pass except `oam_stress`, which is an accepted deviation: it hammers OAM for thirty
seconds with rendering switched off and does not refresh it anywhere near often enough to beat a
millisecond of DRAM charge, so it would only pass with the decay stretched to something like a
twentieth of a second. blargg's own readme says the ROM "passes only for one of the four random
PPU-CPU synchronizations at power/reset" on a real console.

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