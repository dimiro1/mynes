# Working on MyNES

A NES emulator in Java 25, built with Maven. Four modules -- `mynes-core`, `mynes-patch`,
`mynes-headless`, `mynes-desktop` -- and `mvn -B test` at the root still runs everything.

## Seeing what the emulator does

**Use headless mode.** Do not write a harness that reaches into `GameUIFrame`'s private fields, and
do not photograph the screen with `java.awt.Robot`. Everything those used to do is a flag now, and
a flag survives a refactor.

Build the jar once, then run it as often as you like:

```sh
mvn -B package -DskipTests
JAR=mynes-desktop/target/mynes.jar

java -jar $JAR --headless --rom ROM.nes --frames 900 \
    --input 60/40x3:start --screenshot 300,last --audio --dump ram
```

The jar is under `mynes-desktop/` because that is the module with a main class in it, but it is
still one file and still the whole emulator: the fat jar flattens all three modules and their
dependencies into it.

`java -jar $JAR --headless --help` lists every option. Maven can run it too --
`mvn -q compile exec:exec@headless -Dmynes.args="--rom ROM.nes --frames 300"` -- but it costs a
couple of seconds a run against the jar's third of one, so build the jar for anything iterative.
`mvn -q compile exec:exec` opens the window, which a cloud workspace has nowhere to put. Neither
needs a `-pl`: the modules that have no main class declare the goal skipped, and it runs from the
root of the checkout, so a `--rom` path means what it looks like.

### What you get, and where

Everything under `--out`, which defaults to `target/headless`:

| File | What it is |
|---|---|
| `report.json` | Where the machine ended up, and what the picture and the sound were like. Also printed, so `--report - \| jq .` works. |
| `frame-000300.png` | The picture at that frame. **Read these** -- it is the only way to see whether a change actually worked. |
| `audio.wav` | The APU's output, with `--audio`. Peak, RMS and silent-frame counts are in the report either way. |
| `ram.bin`, `oam.bin`, … | With `--dump ram,oam,palette,nametables,prgram,chr` or `--dump all`. |

The report describes the picture for you: `video.finalFrame.blank` (one flat colour, which is what a
machine that never started looks like), `uniqueColours`, `topColours`, a `hash` of the visible 224
lines, and `video.frameChanges` -- how many frames differed from the one before. That last number is
the one that catches a game sitting on its menu.

### A game will not start unless you press something

Left alone, **Super Mario Bros., Super Mario Bros. 3 and Tetris never leave their title screens** --
same picture forever, nothing written to the sound registers. Only Super Mario Bros. 2 plays
untouched. A run with no `--input` is a run of the menu, and an APU that looks completely dead is
usually a game nobody asked to play.

`--input 60/40x3:start` gets all four past their menus. The `x3` matters: Start is also the pause
button, so a pulse that keeps going pauses the game it just started.

```
60:start          press on frame 60
200-400:right     hold from 200 until 400, 400 excluded
60/40:start       press on 60, then every 40 frames
60/40x3:start     the same, three times only
```

Combine with `+` (`a+right`) and separate with commas. No spec contains a space.

### Exploring rather than checking

When you do not yet know what to ask for, `--interactive` takes commands on standard input and
answers each with one line of JSON. Pipe a whole session in:

```sh
printf 'run 60\nscreenshot /tmp/title.png\npress start\nrun-until-change 300\nstate\nquit\n' \
  | java -jar $JAR --headless --rom ROM.nes --interactive
```

`run-until-change` answers "which frame does something happen on". `help` lists the rest.

### Stopping somewhere in particular

The same session takes breakpoints, watchpoints and single steps, which is how to answer questions a
frame boundary is too coarse for:

```
step [N]                   advance N instructions rather than N frames
disasm [ADDR] [COUNT]      disassemble, from the PC by default
break ADDR / unbreak ADDR  stop before the instruction at ADDR
watch ADDR / unwatch ADDR  stop after an instruction writes to ADDR
points [clear]             list them, or drop them all
```

`run` and both `run-until` commands then come back with `stopped` and `stoppedAt` when one of these
ended them early, and a watchpoint also reports `address`, `value` and **`writtenBy`** -- the
instruction that did the store, which is the whole point of setting one. `stopped` is absent rather
than null when nothing stopped the run, so `jq 'select(.stopped)'` is the whole of the filter.

```sh
printf 'watch $0300\nrun 600\nquit\n' | java -jar $JAR --headless --rom ROM.nes --interactive
```

Two things to know. **The first `step` is the reset sequence** and runs no instruction: it leaves the
CPU standing on the first one rather than past it. And **a watchpoint sees CPU writes only** -- a
sprite DMA copies into OAM by calling the PPU directly, so a watch on $2004 sleeps through all 256 of
the writes one makes.

None of this costs anything when it is not used. `Debugger.isArmed()` is asked once a frame, and a
session with nothing set runs the same loop it always did; only an armed one drops to clocking the
machine an instruction at a time.

### Failing on purpose

`--expect-not-blank`, `--expect-audio` and `--expect-motion N` make a run exit 4 when they do not
hold, which is what to use in a script. Anything more particular belongs in `jq` over the report.
Other codes: 2 a bad command line, 3 a timeout, 5 not a cartridge, 1 anything else.

Those numbers only survive the jar. Through Maven any of them fails the build and you get Maven's
own 1 back, so read `exitCode` out of the report if you need to tell them apart.

### Two consoles

`--region ntsc|pal` overrides the cartridge header, which usually says nothing and so means NTSC.
They are not one machine at two speeds: 312 scanlines against 262, 3.2 dots to a CPU cycle against
3, 50.0070 frames a second against 60.0988, and a different APU table for everything counted in CPU
cycles. So **a PAL run and an NTSC run of the same ROM are not comparable** -- `run.region` in the
report is part of what to check before diffing two of them, alongside `run.state.startedFromPowerOn`.

Everything that differs is in `Region`, including the PPU's OAM decay window, which has to outlast
the machine's own blanking interval or every sprite in the game vanishes once a frame. Its tables
are `static` on purpose: `SaveStateCompletenessTests` vandalises every primitive array it can reach
through the console, and an `int[]` field on `Region` would be one of them.

### It is deterministic

Nothing in the machine reads a clock or a random number, so the same ROM, input and frame count give
identical bytes every time. Everything that legitimately varies is under `host`, so
`diff <(jq 'del(.host)' a.json) <(jq 'del(.host)' b.json)` compares two runs. Rely on this: a frame
hash is a fair regression test, and starting again from power on is cheap enough that there is never
a reason to keep a session alive.

### Skipping the boring part

`--save-state FILE` and `--load-state FILE` cut the wait when the same two hundred frames of title
screen are in the way of every run. Get past it once, then start from there:

```sh
java -jar $JAR --headless --rom ROM.nes --frames 200 --input 60/40x3:start --save-state level1.mn
java -jar $JAR --headless --rom ROM.nes --load-state level1.mn --frames 300 --screenshot last
```

A state carries the whole machine, so this is exact rather than approximate -- resuming from one and
running straight through give byte-identical pictures. It has to be the same ROM; anything else is
exit 2.

**But it breaks the assumption above.** Two runs are only comparable when they started the same way,
so `run.state.startedFromPowerOn` in the report is part of what to check before diffing two of them.
`--sram-in`/`--sram-out` do the same for battery RAM, in the `.sav` format other emulators read.

### Running a romhack

`--patch FILE` applies an IPS patch to the ROM before anything reads it as a cartridge. Repeatable,
applied in the order given.

```sh
java -jar $JAR --headless --rom ROM.nes --patch hack.ips --frames 120 --screenshot last
```

**Nothing is written back.** The patch happens to the copy in memory, so the `.nes` on disk is
untouched and there is no patched file to tidy up afterwards. Two consequences worth knowing. The
patch is applied *before* `Cart.load`, so it may rewrite the iNES header and change the mapper, the
bank count or the size of the cartridge. And `cart.sha256` in the report is the digest of the
**patched** image, since that is what ran -- so a patched run and an unpatched one are two different
cartridges as far as the report and a save state are concerned, which is the answer that keeps a
hack's save states out of the original.

`cart.patches` lists each one with the number of records it held. **Zero records is the thing to
look for**: a patch cut against a different dump of the same game applies without complaining and
changes nothing anybody can see. So does one cut against a headerless dump, which will write
everything sixteen bytes early instead -- offsets count from the front of the file, header included.

`RomHackTests` is the worked example: a public-domain hello-world cartridge, a checked-in `.ips` that
rewrites the string it draws, and the two pictures compared. `src/test/resources/PROVENANCE` says
where the cartridge came from and why that one.

## What gets released

`mvn package` also writes `mynes-desktop/target/mynes-<version>.zip` -- the jar, a launcher for each
kind of shell, and the licences. `scripts/smoke-distribution.sh` unpacks it and runs a cartridge out
of it, and both workflows call that, so a distribution somebody has broken fails on the pull request
that broke it rather than at the moment a tag is pushed.

Two things in there are counted rather than derived, and have to move when what they count does. The
smoke test expects **12 palettes** -- `Palettes.NESDEV` plus the eleven under `/palettes` -- because a
palette that will not load is dropped with a warning rather than an exception, so nothing else in the
build would notice one going missing. And `THIRD-PARTY.md` names the two libraries the fat jar
carries, which is the file to write in if a third ever earns its place.

Releasing is a tag and nothing else. `.github/workflows/release.yml` refuses one whose name disagrees
with the pom, so the version moves first and `git tag v<version>` follows it. There are five poms to
move it in now, which is a job for the tool rather than for five edits:

```sh
mvn -B versions:set -DnewVersion=0.3.0 -DprocessAllModules -DgenerateBackupPoms=false
```

The workflow's check reads the root pom, and `help:evaluate` is an aggregator goal, so it still
answers with one version rather than three.

## House style

The code has a strong voice. Match it rather than the language's defaults.

- **Javadoc says *why*, not *what*.** `getFrame()` needs no comment; the reason the NMI is sampled
  one dot into `NES.tick()` needs three paragraphs, and has them. If a comment restates the code,
  delete it. If a decision would look wrong to the next reader, explain it where they will be
  standing.
- **British spelling**: `colour`, `colourise`, `magnitude`. There is a `NESPalette.colours()`.
- `var` for locals, `final` on parameters and fields.
- **No new runtime dependencies without asking.** There are two, FlatLaf and MigLayout, and each one
  earns its place. Jackson is test scope, where it parses the Tom Harte fixtures; the headless mode
  writes its reports through `headless/Json`, which is a writer and must not grow into a parser.
- Loggers are `System.Logger`, named with a short string -- `"UI"`, `"EMU"`, `"HEADLESS"` -- not with
  a class. Build the message with `+` rather than with `{0}` placeholders: `System.Logger` formats
  through `MessageFormat`, which would print a cycle count as `8,934,159`, in whatever the machine's
  locale thinks the separator is.
- Tests are `class FooTests`, package-private, no `@DisplayName`, with method names that read as
  sentences: `aFrameIsColouredThroughTheChosenPalette`, `theCropTakesEightScanlinesFromEachEnd`.

## Layout

Four Maven modules, and the arrows between them only point one way.

```
mynes-core/           depends on nothing
  mynes/              the console: CPU, PPU, APU, BUS, MMU, VRAM, Cart, Region, controllers
  mynes/mappers/      mappers 0 to 4
  mynes/state/        save states and battery .sav files
  mynes/debug/        the disassembler and the breakpoints, shared by the window and the REPL
  mynes/video/        colour indices to pixels: the overscan crop and the frame renderer
  mynes/palette/      the measured RGB tables, and the loader that reads them out of /palettes

mynes-patch/          depends on nothing either, core included
  mynes/patch/        IPS patches, applied to a byte[] before anyone reads it as a cartridge

mynes-headless/       depends on core and patch
  mynes/headless/     the command line mode

mynes-desktop/        depends on core, patch and headless; FlatLaf and MigLayout live here
  mynes/ui/           the Swing window, Main, the key bindings, the CHR viewer, the debugger
```

`mynes-patch` is beside the console rather than inside it because IPS says nothing about what it
patches -- a ROM, a save file, a disk image -- and a patcher that could see a `Cart` would sooner or
later be handed one. It is the front ends that join the two together, both by reading the file,
patching the bytes and handing the result to `Cart.load`. A patch is entitled to rewrite the iNES
header, so it has to be applied *before* the cartridge is parsed rather than after.

The core knows nothing about the front end, and now it *cannot*: `Cart.load` takes a `byte[]`, `NES`
has no UI dependency, `nes.tick()` is the only clock, and the PPU emits colour *indices* -- never
RGB, because which RGB is a question about televisions. What used to be a rule about imports is a
rule about the class path. A chip that wants a window, or a REPL that wants a `JDialog`, does not
compile.

Which makes one check worth running when the dependencies change:

```sh
mvn dependency:tree -pl mynes-core       # nothing but the two test artifacts
mvn dependency:tree -pl mynes-patch      # nothing but JUnit
mvn dependency:tree -pl mynes-headless   # no FlatLaf, no MigLayout
```

The palettes are in the core rather than beside the window because both front ends draw with them
and neither owns them. `NESPalette` is 512 packed integers and `Palettes` reads files; the one piece
of Swing in that story, `PaletteDialog`, stayed behind in `mynes/ui/`.

`peek` means "read without side effects", and it is load-bearing. `VRAM.read` tells the mapper what
address is on the bus, and MMC3 counts those to drive its scanline interrupt -- so a debugger that
dumped memory through `read` would fire interrupts the game never asked for. Use `peek`.

Cartridge RAM is the one memory `peek` is *wrong* for. `MMU.peek` at $6000 falls through to the
mapper, and MMC1 and MMC3 read back zero when the game has switched the chip off -- which is exactly
what a battery board does around anything risky. Use `Mapper.prgRAM()`, which is the chip rather than
the bus.

Adding a field to any of the chips means adding it to that class's `serialize`, or adding it to
`NOT_IN_THE_STATE` in `SaveStateCompletenessTests` with a reason. That test walks the console
reflectively and will fail otherwise, which is the point of it -- one list of fields, used for both
saving and loading, so a field can only be forgotten in both directions at once.
