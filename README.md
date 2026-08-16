# MyNES

A NES emulator written in Java. Still a work in progress, but it plays games.

![Super Mario Bros. 3](shots/game-smb3.png)

## Downloading it

There is a zip on the [releases page](https://github.com/dimiro1/mynes/releases). Unpack it and run
the script inside:

```sh
unzip mynes-0.2.0.zip
cd mynes-0.2.0
./mynes
```

`mynes.bat` is the same thing for Windows, and `mynes.jar` is what both of them run, so
`java -jar mynes.jar` works too, as does double clicking it. One zip covers macOS, Windows and
Linux: the libraries the emulator uses carry their native code for all three, and the jar carries
the libraries.

You still need Java 25. The zip holds the emulator and nothing else, so if you have not got one,
[Adoptium](https://adoptium.net) has one for every machine this runs on.

Then **File > Open...** and pick a `.nes` file. No ROMs are included, so bring your own. Games run at
60.0988 frames a second, or 50.0070 on a PAL cartridge, with the overscan cropped and the picture
scaled to the window.

## Building it

You need Java 25 and Maven. Nothing else.

```sh
git clone https://github.com/dimiro1/mynes.git
cd mynes
mvn -q compile exec:exec
```

That opens the window. Or build a jar once and run that:

```sh
mvn -B package -DskipTests
java -jar target/mynes.jar
```

The same `mvn package` also writes the release zip into `target/`, so what the releases page carries
is never anything a build here has not already made.

## Controls

| NES button | Key |
|---|---|
| D-pad | Arrow keys |
| A | X |
| B | Z |
| Start | Enter |
| Select | Shift |
| Quick Save | F5 |
| Quick Load | F7 |
| Screenshot | F12 |

**Settings > Controller...** remaps any of them. Click a button, press the key you want on it, and
that is it: there is no save button. Your choices land in `~/.mynes/config.properties`, along with
the palette, screen size and fast forward speed:

```properties
video.palette=nesdev
video.palette.pal=2c07
video.scale=2
video.screenshot.scale=1
emulation.region=auto
emulation.fast-forward=4x
audio.muted=false
controller1.a=VK_X
controller1.left=VK_LEFT
```

You can edit that file by hand instead. Key names are the `VK_` constants from
`java.awt.event.KeyEvent`, and an empty value leaves a button unbound. Anything missing or
misspelled falls back to its default and says so in the log.

## What works

**The console.** The CPU runs cycle by cycle and the PPU dot by dot: background and sprite
pipelines, the loopy scroll registers, sprite 0 hit, the sprite overflow bug, the delayed $2006 VRAM
address, the write-ignore window the chip holds over $2000, $2001, $2005 and $2006 while it warms
up, open bus decay, OAM decay and colour emphasis. The PPU fills a 256x240 buffer with colour
*indices*; turning those into pixels is the palette's job, not the chip's.

**Sound.** The whole 2A03 APU: two pulses with their sweep units, the triangle, the noise channel,
and the DMC with its DMA, plus the frame counter and both of its interrupts. It is mixed through the
hardware's two nonlinear ladders, filtered the way the console's output stage is, and played at
44.1 kHz through `javax.sound.sampled`. A computer with no sound device runs silently and says so in
the log.

**Eleven mappers:** NROM, MMC1, UxROM, CNROM and MMC3 (0 to 4), the last of those with its
scanline IRQ; then AxROM (7), MMC2 and MMC4 (9 and 10), Color Dreams (11), GxROM (66) and the
Camerica boards (71). MMC2 and MMC4 are the odd ones: their pattern tables switch themselves from
whichever tile the PPU last fetched, with no code involved, which is how Punch-Out!! animates two
boxers out of far more tiles than fit in the window.

**Both consoles.** The NTSC machine and the PAL one, which are not the same machine at a different
speed: the PAL PPU draws 312 scanlines instead of 262 and takes 3.2 dots to a CPU cycle instead of
3, and every table in its APU counted in CPU cycles is a different table. The cartridge header is
believed where it says anything, which is not often — the field was an afterthought and most dumps
leave it blank — so **Machine > Region** is there to insist. A game running 17% fast with the music
too high is the symptom to reach for it over.

**Twelve palettes:** the NESdev set, ten measured NTSC ones from firebrandx.com — among them NES
Classic, PVM Style D93, Smooth and Wavebeam — and one for the PAL chip, whose colourburst sits
fifteen degrees away from the NTSC one and which therefore needs a table of its own. PAL cartridges
get that one by default. **Settings > Palette...** lists them next to a swatch grid and applies each
one as the selection moves, so you can compare them against the running game, or against a paused
frame; the choice is remembered separately for each kind of machine.

**Screen size** at 1x, 2x, 3x or 4x of the 256x224 picture, from **Settings > Screen Size**, which
packs the window around it. Whole multiples only, so every NES pixel comes out the same size as
every other one. You can still resize the window by hand, and the picture is fitted and letterboxed
to keep its shape; the menu is how to get back to a clean multiple.

**Screenshots** from **File > Screenshot**, or `F12`, which writes a PNG beside the ROM named after
it and stamped with the time — no dialog, since a picture that took one to save would be a picture
of the moment after the one worth keeping. **Settings > Screenshot Size** magnifies it by 1x to 4x
on the way out; 1x is the 256x224 the machine drew. It is the picture rather than the window: the
overscan is cropped the way the window crops it, the magnification is a whole number whatever size
the window has been dragged to, and it works on a machine that is paused or stopped at a breakpoint.

**Fast forward** at 2x, 4x, 8x or unlimited, from the Machine menu. The console is clocked exactly
as it always is. What shrinks is the wait between frames, and the display still shows sixty a
second, so you see the ones that fall due. Asking for more than the computer manages is not an
error; you simply get whatever it manages. Sound cannot be handed to a sound card faster than real
time, so audio comes out chopped rather than sped up.

**The rest of the Machine menu:** Reset (the console button, memory survives), Power Cycle, Region,
Pause and Mute. Changing the region starts the game again from power on, since the chips are built
around it and a running machine cannot be rewired. Mute is remembered between runs; fast forward is not. Muting does not tell the machine
anything, so a silenced APU still runs and still raises its interrupts, and a game behaves the same
either way.

**Debug tools.** A debugger stops the machine where you tell it to: breakpoints on an address,
watchpoints on a write, single stepping by instruction or by frame, a live disassembly with the
history of what actually ran above it, the registers and where the beam is, and a hex view of the
whole address space. A watchpoint says which instruction did the writing, which is the question
worth asking. It reads the machine only while it is stopped, so what it shows is one moment rather
than a blur of several.

A CHR viewer shows every tile of a bank with a zoomed preview, coloured with any of the eight
palettes the game is using, and it updates live as CHR RAM is rewritten and palettes change. There
are also toggles to hide the background or the sprite layer without the game noticing.

All of it is in headless mode too — `break`, `watch`, `step` and `disasm` are commands in the
interactive session, so the same questions can be asked from a script.

**Save states and battery saves**, and a **headless mode** for running with no window at all. Both
have a section of their own below.

Not there yet: a second player, and Dendy, the Russian famiclone, which a cartridge that asks for it
gets run as PAL instead.

## Screenshots

### Super Mario Bros. (mapper 0, NROM)

![Super Mario Bros.](shots/game-smb.png)

### Tetris (mapper 1, MMC1)

![Tetris](shots/game-tetris.png)

### Super Mario Bros. 2 (mapper 4, MMC3)

![Super Mario Bros. 2](shots/game-smb2.png)

### The palette picker

Every selection takes effect the moment it is made, so the picture behind the dialog is the
comparison. It recolours a paused frame just as well as a running one.

![The palette dialog over a running game](shots/palette-dialog.png)

### The CHR viewer

A bank of Super Mario Bros. 3's character ROM, drawn with one of the eight palettes the game is
running with.

![The CHR viewer](shots/chr-viewer.png)

### The controller dialog

![The controller dialog](shots/controller-dialog.png)

## Saving

There are two kinds of save file here, and they are not two versions of the same idea.

**Save states** are the whole console frozen to a file: every register, both RAMs, the palettes, the
half-executed instruction, where the beam is. **Machine > Save State** and **Machine > Load State**
each hold nine slots. `F5` and `F7` use the current slot, and picking a slot from either menu makes
it the current one. States live beside the ROM as `GAME.mn1` through `GAME.mn9`, and the Load State
menu labels each slot with the frame it was taken on and when.

They load in MyNES and nowhere else. There is no standard format for save states and there never has
been: cross-emulator states have been proposed on NESdev more than once and abandoned every time,
because every emulator models the pipeline differently and there are hundreds of mappers. A state
taken from one cartridge is refused by another.

**Battery saves** are the eight kilobytes a coin cell kept alive on the cartridge, and this format
*is* standard: `GAME.sav`, raw bytes, no header, no version. FCEUX, Nestopia and Mesen all read and
write exactly this, so a save from any of them can be dropped next to the ROM and it just works.
MyNES reads it when the cartridge loads, and writes it when the window closes, when the cartridge
changes, and once a minute if the game has saved anything since. Only cartridges whose header claims
a battery get one. The RAM at $6000 that a test ROM prints its results through is scratch, and
persisting it would invent saves nobody made.

The difference is worth keeping in mind. A save state is a bookmark, and losing one costs a few
minutes. A `.sav` is fifty hours of Zelda, which is why that one is written to a temporary file and
moved into place, so that a crash halfway through cannot take both it and its replacement.

## Headless mode

The emulator also runs with nobody watching: no window, no sound card. That is useful from a script,
on a machine with no display, or for a coding agent that cannot look at a window.

```sh
mvn -B package -DskipTests
java -jar target/mynes.jar --headless \
    --rom smb.nes --frames 900 --input 60/40x3:start --screenshot 300,last --audio
```

That runs 900 frames, presses Start to get past the title screen, and writes two PNGs, a JSON report
and a WAV into `target/headless`. Maven can run it too, and is shorter to type if you only need it
once:

```sh
mvn -q compile exec:exec@headless -Dmynes.args="--rom smb.nes --frames 900 --screenshot last"
```

The jar is worth building for anything you run more than a few times: Maven takes a couple of
seconds to start up, the jar about a third of one.

`--headless --help` lists every option. The things worth knowing before you read it:

- **Most games never start on their own.** Super Mario Bros., Super Mario Bros. 3 and Tetris all sit
  on their title screens for as long as anyone cares to wait, drawing the same picture and writing
  nothing to the sound registers. Only Super Mario Bros. 2 plays untouched. `--input 60/40x3:start`
  presses Start on frame 60 and twice more, which gets all four into their first level. The `x3`
  matters: Start is also the pause button.
- **The report describes the picture** for anything that cannot look at it: a hash of the visible
  224 lines, how many colours are in the frame, whether it is one flat colour, and how many frames
  differed from the frame before. That last number is the one that catches a game stuck on its menu.
- **`--expect-not-blank`, `--expect-audio` and `--expect-motion N`** turn three of those questions
  into a pass or a fail. The run exits 4 when they do not hold, which is what a script wants.
- **Runs are deterministic.** Nothing in the machine reads a clock or a random number, so the same
  ROM, input and frame count produce identical bytes on every run and every computer. Anything that
  legitimately varies lives under `host` in the report, so
  `diff <(jq 'del(.host)' a.json) <(jq 'del(.host)' b.json)` compares two runs.
- **`--region ntsc|pal`** overrides what the cartridge's header asks for. Two runs in different
  regions are not two measurements of the same thing — a frame is 106392 dots on one machine and
  89342 on the other — so `run.region` in the report is part of what to check before diffing them.
- **`--save-state` and `--load-state`** cut the wait when the same two hundred frames of title
  screen are in the way of every run. `--sram-in` and `--sram-out` do the same for battery RAM, in
  the `.sav` format other emulators read.
- **`--interactive`** reads commands on standard input and answers each with a line of JSON, for
  when you do not yet know the question well enough to write it down. It is also where the debugger
  lives without a window: `break`, `watch`, `step` and `disasm`, with `run` reporting back what
  stopped it.

[CLAUDE.md](CLAUDE.md) covers all of this in more detail. It is written for coding agents, but it is
just as accurate for people.

## Tests

```sh
mvn test
```

That runs everything.

**The CPU** is checked against the
[Tom Harte SingleStepTests](https://github.com/SingleStepTests/65x02): all 256 opcodes, 500 cases
each from a committed subset, and each case twice. Once for the end state and cycle count, once for
the exact per-cycle bus traffic. nestest passes as well.

For the full 10,000 cases per opcode, fetch the upstream set once:

```sh
./scripts/download-6502-tests.sh   # ~1.4GB into the gitignored testdata/
mvn test                           # picks the full set up automatically
```

The bus trace stage is the slow half. It can be run on its own, or skipped:

```sh
mvn test -Dgroups=bus-trace
mvn test -DexcludedGroups=bus-trace
```

**The PPU** runs blargg's test ROMs, vendored under `src/test/resources` together with the readme
that came with each suite: `ppu-vbl-nmi` (VBlank and NMI timing to a single PPU clock),
`ppu-sprite-hit`, `ppu-sprite-overflow`, `oam` (`oam_read` and `oam_stress`), `ppu-open-bus`,
`ppu-read-buffer` and `ppu-tests-2005` (palette RAM, VRAM access, sprite RAM, VBlank clear time).

All of them pass except `oam_stress`, which is an accepted failure. It hammers OAM for thirty
seconds with rendering switched off and never refreshes it often enough to beat a millisecond of
DRAM charge, so it would only pass with the decay stretched to something like a twentieth of a
second. blargg's own readme says the ROM "passes only for one of the four random PPU-CPU
synchronizations at power/reset" on a real console.

These ROMs report in two different ways, both handled by `BlarggRunner`: the later ones use the
$6000 status protocol, and the 2005 era ones write a numeric result code into zero page. A failure
code means nothing on its own, so look it up in the `readme.txt` vendored next to the ROM.

**The APU** runs blargg's `apu_test` and `apu_reset` suites, and all fourteen ROMs pass. Between
them they cover the length counters and their table, the frame counter's interrupt and the exact
cycle it lands on, the clock jitter that comes of the APU running at half the CPU's rate, the DMC's
sample handling and its sixteen rates, and what the chip looks like at power on and after Reset.

**MMC3** runs `mmc3-test-2`, which covers the scanline counter. Four of its six ROMs pass, and the
two that do not are commented out in `MMC3BlarggTests` with the reason. `4-scanline_timing` wants
the interrupt to land on an exact PPU dot, which needs the PPU to hold each fetch address on the bus
between accesses rather than only during them. `6-MMC3_alt` tests the revision A counter, which
contradicts `5-MMC3` by design. No real chip passes both.

**The whole console at once** runs 100thCoin's
[AccuracyCoin](https://github.com/100thCoin/AccuracyCoin), one NROM cartridge carrying 141 scored
tests and vendored under `src/test/resources` like the rest. With the cursor on a page header, Start
runs every one of them and draws the table below: a column per page of the menu and a tile per test,
red with an error code on it where one failed, and a pale number where the ROM accepts more than one
answer and that is which one came back. 134 pass.

![AccuracyCoin's results table](shots/accuracycoin.png)

`AccuracyCoinTests` runs the same 141 and holds each result against a table of what it currently
reports, so a result that moves fails the build in either direction. A pass that became a failure is
a regression; a failure that became a pass means the expected-failure list has outlived the bug it
described, and the entry has to come out of it.

The seven still red are three things between them. `ALE + Read`, `Hybrid Addresses` and the
`$2007 Stress Test` want the PPU to hold each fetch address on its bus for both of the dots the read
takes, so that a CPU access landing in the middle of one corrupts it — which is what
`4-scanline_timing` above is waiting on as well. `APU Register Activation` and `Internal Data Bus`
want the 2A03's two data buses kept apart: a read from $4015 updates the internal one and leaves the
external one alone, and nothing else in the machine tells them apart. `$2004 Stress Test` and
`BG Serial In` are each their own — what the OAM buffer holds while sprite evaluation runs OAMADDR
past the end, and the bit the background shift registers clock in when nothing reloads them.

## Licence

MIT. See [LICENSE](LICENSE).
