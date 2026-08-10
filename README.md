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
- Machine menu: Reset (the console button, memory survives), Power Cycle, Pause, Mute, and Fast
  Forward at 2x, 4x, 8x or unlimited -- the machine is clocked exactly as it always is, the wait
  between frames is what shrinks, and the picture keeps its sixty frames a second by showing only
  the ones that fall due;
- 2A03 APU: two pulses with their sweep units, the triangle, the noise channel and the DMC with
  its DMA, the frame counter and both of its interrupts, mixed through the hardware's two
  nonlinear ladders and filtered the way the console's output stage is, out through
  `javax.sound.sampled` at 44.1kHz;
- Keyboard control of player one, remappable;
- CHR debug window: every tile of a bank with a zoomed preview, coloured with any of the eight
  palettes the game is running with, live -- CHR RAM rewrites and palette changes show up as
  they happen;
- Debug toggles to hide the background or sprite layer without the game noticing;
- Most of blargg tests are passing;
- nestests is passing;
- Per-opcode verification against the Tom Harte single step tests;

Mappers 0 (NROM), 1 (MMC1), 2 (UxROM), 3 (CNROM) and 4 (MMC3, with the scanline IRQ) are
supported. There is no second player, no PAL timing and no save states.

## Screenshots

### Super Mario Bros. -- mapper 0, NROM

![Super Mario Bros.](shots/game-smb.png)

### Tetris -- mapper 1, MMC1

![Tetris](shots/game-tetris.png)

### Super Mario Bros. 2 -- mapper 4, MMC3

![Super Mario Bros. 2](shots/game-smb2.png)

### Super Mario Bros. 3 -- mapper 4, MMC3

![Super Mario Bros. 3](shots/game-smb3.png)

### Palette

Every selection takes effect the moment it is made, so the picture behind the dialog is the
comparison -- and it recolours a paused frame just as well as a running one.

![The palette dialog over a running game](shots/palette-dialog.png)

### CHR Viewer

A bank of Super Mario Bros. 3's character ROM, drawn with one of the eight palettes the game is
running with.

![The CHR viewer](shots/chr-viewer.png)

### Controller

![The controller dialog](shots/controller-dialog.png)

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
chosen palette and fast forward speed:

```properties
video.palette=nesdev
emulation.fast-forward=4x
audio.muted=false
controller1.a=VK_X
controller1.left=VK_LEFT
```

That file can be edited by hand instead. Binding values are the names of the `VK_` constants in
`java.awt.event.KeyEvent`, an empty value leaves the button unbound, and an entry that is missing
or misspelled -- a key name, a palette id, a speed -- falls back to its default and says so in the
log.

Machine > Fast Forward switches the speed on and off, and Machine > Fast Forward Speed picks which
one. Whether it is on is not remembered between runs; which speed it uses is. Asking for more than
the computer manages is not an error -- it simply runs at whatever it manages, which on the machine
this was written on is around ten times, so 4x and 8x are kept and `unlimited` is the rest of it.

Machine > Mute silences the sound, and unlike fast forward it is remembered. The machine itself is
not told: a muted APU still runs and still raises its interrupts, so a game sounds and behaves the
same either way. Fast forwarding cannot hand a sound card audio faster than real time, so what does
not fit is dropped and the sound comes out chopped rather than sped up. A computer with no sound
device runs silently and says so in the log.

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

### APU test ROMs

`apu-test` is blargg's `apu_test` suite, `apu-reset` his `apu_reset` suite, and all fourteen ROMs
pass. Between them they cover the length counters and their table, the frame counter's interrupt
and the exact cycle it lands on, the clock jitter that comes of the APU running at half the CPU's
rate, the DMC's sample handling and its sixteen rates, and what the chip looks like at power on and
after the Reset button.

### Mapper test ROMs

`mmc3-test-2` covers the MMC3 scanline counter. Four of its six ROMs pass; the two that do not
are commented out in `MMC3BlarggTests` with the reason. `4-scanline_timing` wants the interrupt
to land on an exact PPU dot, which needs the PPU to hold each fetch address on the bus between
accesses rather than only during them. `6-MMC3_alt` is the revision A counter, which contradicts
`5-MMC3` by design -- no real chip passes both.

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