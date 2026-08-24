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
report is part of what to check before diffing two of them, alongside `run.state.startedFromPowerOn`,
`run.hacks` and `run.genie`.

`run.hacks` is the third thing in that list, and it is there for the same reason: `--hack
unlimited-sprites` draws the sprites the chip would have dropped, so a scanline holding more than
eight of them stops flickering. Nothing a game can observe changes -- the overflow flag still rises,
$2004 still answers with what the sprite hardware is holding, and the cartridge sees the same address
bus, which is what keeps MMC3's scanline counter honest -- but the picture is not the one the
hardware would have drawn, so two runs that disagree about it are not two measurements of the same
thing. Every hack is off unless it is named. `hack unlimited-sprites on|off` does the same thing
inside an interactive session, which is how to take the same frame twice and diff the pictures.

Do not go looking for a game to see it on. A cartridge is written to stay under eight sprites a line
and they mostly manage it -- Punch-Out!!'s first fight peaks at seven, Battletoads' first level at
eight -- so the demonstration is `sprite-limit/sprite-limit.nes`, which puts all sixty four on one
line and is assembled by `SpriteLimitROM` beside it rather than vendored as bytes.

**The other hack is a timing hack, and that is a different kind of thing.** `--hack overclock=131`
makes the PPU idle through 131 extra scanlines a frame, which is about 113.67 CPU cycles each on
NTSC, so a game whose main loop overruns its frame stops dropping one -- the every-other-frame
stutter Super Mario Bros. 3 and Gradius get under load. The picture is the hardware's, dot for dot,
because the extra lines are lines the beam is already idle on; the sound is a hardware frame's worth
too, because `APU.idle()` holds the sound chip still through them, so `audio.samples` and the music's
pitch and tempo do not move. What does move is **what the game does**, so two runs that disagree
about `run.hacks.overclock` are two different games rather than two views of one -- which is why,
unlike unlimited-sprites, it rides inside a movie, is refused while one is recording, and is refused
alongside `--play`.

`--hack overclock=131+20` puts twenty of the lines after the NMI instead. **Reach for the
before-NMI number.** Extra post-render lines break nothing a game observes except that the frame is
longer; extra vblank lines move the pre-render line -- and so the picture -- relative to the NMI,
which is exactly what code that cycle-counts down to a mid-screen split is measuring. Either way the
pre-render line arrives later in CPU cycles, so a program that waits out the PPU's warm-up by
counting 29658 cycles rather than by waiting for two VBlanks has its first `$2000`/`$2001` writes
dropped -- the same class of difference PAL's fifty extra lines make. `hack overclock LINES [MORE]`
and `hack overclock off` do it inside an interactive session, and `Overclock.percentOf` is what turns
the desktop's percentages into lines.

Do not go looking for a game to see this one on either. A game only lags where it is loaded, which
is not somewhere a test can reliably reach, so the demonstration is `overclock/overclock.nes`: a lap
of its main loop takes 42500 cycles, which is 1.43 NTSC frames, so it finishes one lap every two
frames on the hardware and one a frame at `overclock=131`. It counts frames at `$00-$01` and laps at
`$02-$03`, so `--dump ram` reads the answer, and it recolours the whole screen once a lap so
`video.frameChanges` says the same thing. `OverclockROM` assembles it, beside `SpriteLimitROM`.

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

### Going back rather than starting again

`rewind on` keeps a state for every finished frame, and `rewind N` puts the machine back N of them.
It is off unless asked for, because capturing costs two to three milliseconds a frame and a headless
run is usually a measurement.

```sh
printf 'rewind on\nrun 90\nrewind 30\nquit\n' | java -jar $JAR --headless --rom ROM.nes --interactive
```

That lands on frame 60 with the same hash a plain `run 60` gives -- a rewound machine is byte for
byte the machine that never went forward, which is the whole claim and is worth re-checking after
touching any of it. `rewind on FRAMES` sizes the ring, the default being thirty seconds for the
region; `rewind` on its own reports `capacity` and `rewindable`; `rewind off` drops the history.
Running out is not an error, and the reply's `framesRewound` is how far it *actually* went.

**`run.state.framesRewound` joins `startedFromPowerOn` in the list of things to check before diffing
two runs.** A session that went back and played the same frames again visited them with the machine
in a state the frame counter no longer describes, so its `frameChanges` and its sound are not a
straight run's. It is always present and 0 when nobody rewound.

The window's ring is not this one: it keeps a state every *other* frame, which halves the cost,
doubles the history for the memory, and gives back two frames per display tick so the rewind runs at
twice speed. The REPL stays at one so that `rewind N` means N frames.

### Recording a session, and playing it back

`--record FILE` writes a `.mnm` movie of the run: where it started, one button mask per finished
frame, and a sparse list of the frames Reset was pressed at. `--play FILE` plays one instead of a
schedule. A replay is byte-identical, which is the whole claim and the thing to check after touching
any of it:

```sh
java -jar $JAR --headless --rom ROM.nes --frames 900 --input 60/40x3:start --reset-at 500 \
    --record take.mnm --save-state a.mn
java -jar $JAR --headless --rom ROM.nes --play take.mnm --save-state b.mn
cmp a.mn b.mn        # byte-identical end state
```

**A rewind is not in the movie.** Rewinding while recording drops the frames that were taken back,
so a movie holds the timeline that was finally played and a replay never re-enacts the revert. That
composes out of the rewind claim above: a rewound machine is byte for byte the machine that never
went forward, so there is nothing lost by truncating the log to match.

`--play` is the input, so it refuses `--record`, `--input`, `--input-file`, `--reset-at`, `--genie`,
`--hack overclock`, `--load-state`, `--sram-in` and `--interactive` -- each of those would be a
second answer to a question the movie has already answered. `--hack unlimited-sprites` still combines
with it, being a change to the picture and to nothing the replay depends on. It defaults `--frames`
to the movie's own length; asking for more runs past the end with nothing held down.

`record`, `record start` and `record stop [PATH]` do the same inside an interactive session -- the
shape of `rewind` rather than of `hack`, since the interesting form is the one that takes a file.
Mutating `genie`/`ungenie`/`genie clear` and `hack overclock` are refused while recording, because a
movie pins both at the moment it starts and a file naming one set of codes, or one number of extra
scanlines, that was played against another cannot be replayed.

**`run.record` and `run.replay` join the comparability checklist**, beside `run.state`, `run.region`,
`run.hacks` and `run.genie`. Both are always present with explicit nulls. A run that started at power
on records a movie that starts there and carries no state at all; anything else -- a `--load-state`,
a `--sram-in`, a loaded state mid-session, or a rewind that went back past the start of the recording
-- puts a save state inside the file, and `run.replay.anchored` is what says so.
`run.state.startedFromPowerOn` is false for a replay of one of those.

The Game Genie codes ride inside the movie and are put back on replay. They have to: a cheated
cartridge is byte for byte an honest one, so `cart.sha256` cannot tell them apart and nothing else in
the file would. **The overclock rides in an `OVCK` chunk beside them**, for the same reason and a
sharper one: it decides how much of its work the game gets through in a frame, so a replay at the
hardware's timing is a replay of a different game rather than of the same game seen differently.

The desktop has **Machine > Record Movie... / Play Movie...**. While either is running the pad is
latched once a frame on the emulation thread rather than reaching the controller the moment a key
moves, and Power Cycle, Region, the Game Genie item and the Overclock submenu are greyed out -- the
first two would build a new machine and take the recorder with it. Rewinding during playback stops it
and hands the game back, and the menu's own overclock goes back on when it does.

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

### Running a Game Genie code

`--genie CODE` puts one in the cartridge slot. Repeatable, comma separated, six letters or eight from
`APZLGITYEOXUKSVN` -- and note what is *not* in that alphabet, because a code read off a website with
a B, a C, a D or an R in it has been mistranscribed.

```sh
java -jar $JAR --headless --rom SMB.nes --genie SXIOPO --frames 900 --input 60/40x3:start
```

**This is not a patch, and the difference is the whole of why it exists.** A patch rewrites the image
before `Cart.load` sees it. A code rewrites nothing: the device sat between the cartridge and the
console, and `MMU` asks it what the console reads after the mapper has answered -- so the
substitution happens per bus cycle, through whatever bank is switched in at that moment. Eight-letter
codes carry a compare byte for exactly that reason, and only fire where the cartridge answers with
it.

Three consequences, in the order they will bite:

- **`cart.sha256` is the plain cartridge's**, since the cartridge really is untouched. So
  `run.genie` is the only thing in the whole report that tells a cheated run from an honest one --
  where `--patch` gives a hack its own digest and a save state of its own, `--genie` cannot, and a
  state taken with codes in **will load into a machine with them out without complaining**.
- **One address holds one code.** The real device answers the bus in the time it has and does not get
  a second look, so a second code for an address replaces the first rather than stacking behind it.
  Deliberately unlike `IPSPatch.applyTo`, where two records may overlap and the later one wins.
- **A code can only reach $8000-$FFFF.** Fifteen address bits and /ROMSEL is all the cartridge port
  carries, so there is no code for cartridge RAM, for the console's own memory, or for anything else.

`genie CODE`, `ungenie CODE` and `genie clear` do the same inside an interactive session -- the shape
of `break`/`unbreak`/`points clear` rather than of `hack`, because a code is put down and picked up
rather than switched. That is how to take the same frame twice and diff the pictures. The cartridge
held three codes; this holds as many as are typed.

`GameGenieRunTests` is the worked example, and it is worth reading before changing any of this: it
makes **the same edit twice**, once as the `.ips` above and once as ten codes at the equivalent CPU
addresses, and asserts the two draw byte-identical pictures. That works because the hello-world
cartridge is 32KB of PRG and so unmirrored, which makes file offset `n` exactly CPU `$8000 + (n-16)`.

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
  mynes/              the console: CPU, PPU, APU, BUS, MMU, VRAM, Cart, Region, Overclock,
                      controllers
  mynes/mappers/      mappers 0 to 4
  mynes/state/        save states, battery .sav files, and .mnm session recordings
  mynes/debug/        the disassembler and the breakpoints, shared by the window and the REPL
  mynes/cheat/        Game Genie codes, and the device MMU asks on every read of PRG ROM
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

`mynes/cheat/` is inside the console for the opposite reason. A Game Genie code says `$8000`, says
"the NES CPU address map", and cannot be decoded without one -- so unlike IPS there is nothing
general about it to keep at arm's length. It is also not applied to anything: `GameGenie` is asked by
`MMU` on every read of $8000-$FFFF, and holds the `MMU` back so it can put its own hook down as the
first code arrives and take it up as the last one goes. **The field on `MMU` is null when there are
no codes, and that is load-bearing twice over**: it is what keeps a machine nobody is cheating on to
a null check on the hottest line in the emulator, and it is what keeps `SaveStateCompletenessTests`
out of the device -- a field holding null is recorded and stepped over, where one holding an object
would be walked into and every array inside it scrambled.

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
