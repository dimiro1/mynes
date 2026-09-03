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
| `audio.wav` | The APU's output, with `--audio`. Peak, RMS and silent-frame counts are in the report either way, and `--mute` takes a voice out of all four. |
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
break ADDR [if COND]       stop before the instruction at ADDR, where COND holds
unbreak ADDR               forget that one
watch ADDR [read|write|both]
                           stop after an instruction touches ADDR that way, writes by default
unwatch ADDR               forget that one
points [clear]             list them, or drop them all
```

`run` and both `run-until` commands then come back with `stopped` and `stoppedAt` when one of these
ended them early, and a watchpoint also reports `address`, `value`, `access` and the instruction that
did it -- **`writtenBy`** for a write and **`readBy`** for a read, which is the whole point of setting
one. Named differently rather than sharing one field, because a `writtenBy` on a stop where nothing
was written would be a lie with a helpful shape. `stopped` is absent rather than null when nothing
stopped the run, so `jq 'select(.stopped)'` is the whole of the filter.

```sh
printf 'watch $0300\nrun 600\nquit\n' | java -jar $JAR --headless --rom ROM.nes --interactive
```

**A read watchpoint is a different question from a breakpoint, and it is worth knowing which one is
being asked.** Every instruction the CPU runs is fetched off the same bus as everything else, so a
read watch on an address inside a routine fires on the fetch, every pass, and reports the opcode
byte. That is what the hardware does rather than an artefact -- but "stop when the machine reaches
here" is `break`, and reaching for `watch ... read` to ask it will bury the answer.

**A condition is a comparison and nothing else.** Either side may be a register (`a`, `x`, `y`, `sp`,
`p`, `pc`), a byte of memory (`[$0300]`) or a number, and the comparisons are `==`, `!=`, `<`, `<=`,
`>` and `>=`. Numbers are decimal unless they say otherwise, the way the rest of the command line has
it, so `break $C000 if a == 16` and `break $C000 if a == $10` are the same point. Memory is read
through `peek`, so a condition on `[$2002]` reads zero rather than clearing the VBlank flag of a game
that was never stopped. One address holds one breakpoint: setting it again is how a condition is
changed, and a bare `break` is how one comes off.

`points` lists both as objects rather than as bare addresses -- `{"address":..., "condition":...}` and
`{"address":..., "on":"read"}` -- because an address on its own stopped being the whole of a point.

None of this costs anything when it is not used. `Debugger.isArmed()` is asked once a frame, and a
session with nothing set runs the same loop it always did; only an armed one drops to clocking the
machine an instruction at a time. The read hook goes on the bus only when a read watchpoint asks for
it, which matters more than the write hook does: everything the CPU fetches goes past it.

**Two things a watchpoint does not see.** A sprite DMA copies into OAM by calling the PPU directly, so
a watch on $2004 sleeps through all 256 of the writes one makes -- and neither a DMA's reads nor the
DMC's sample fetches are the processor reading, so they are invisible to a read watch too. And **the
first `step` is the reset sequence**, which runs no instruction: it leaves the CPU standing on the
first one rather than past it.

### Writing down every instruction

`trace PATH [LINES]` logs one line per instruction in nestest's format, `trace off` stops, and a bare
`trace` says how far it has got. The format is nestest's down to the column because that log is
already this emulator's answer key -- `NesTestTests` walks 8990 of its lines against a running
machine -- so a trace taken here and a log taken from another emulator diff against each other:

```
C000  4C F5 C5  JMP $C5F5                       A:00 X:00 Y:00 P:24 SP:FD PPU:  0, 27 CYC:8
```

**Say how many lines to keep.** A frame is around thirty thousand instructions at about ninety bytes
a line, so `trace out.log` followed by `run 900` is a gigabyte and a half. The shape this is meant to
be used in is to stop somewhere first and then trace from there:

```sh
printf 'break $C5F5\nrun 600\ntrace /tmp/t.log 5000\nstep 5000\ntrace off\nquit\n' \
  | java -jar $JAR --headless --rom ROM.nes --interactive
```

Two columns are not nestest's. It appends `= 00` to an operand to say what the address held, which is
a read, and a tracer that performed one would be changing the machine it is describing. And the
`PPU:` column is where the beam is *as the opcode is fetched*, one CPU cycle later than a log printing
three times its cycle count -- so a cross-emulator diff belongs on the CPU columns, which line up
exactly. `Tracer` is where all of it lives, and the window has the same thing under **Debug > Start
Trace...**

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
`run.hacks`, `run.genie` and `audio.muted`.

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

### Drawing it the way a television did

`--filter ntsc` colours the picture by rebuilding the composite waveform the chip drew and decoding
it, rather than by looking each colour index up in a palette. That is the only way to get the three
things a table cannot produce, because all three depend on the pixel next door: colour bleed, dot
crawl, and the artefact colours a game gets out of alternating narrow columns of grey. A pixel is
eight samples of signal, a colour cycle is twelve, and a 341 pixel scanline is 227 and a third
cycles -- those three numbers are the whole of it, and the last is why the picture drifts a third of
a cycle a line and repeats every third one. `PPU.getFramePhase()` is where the chip's share of that
drift is counted.

**The palette is not consulted while it is on**, and that is not an oversight: a decoder works its
colours out of the signal, and a measured table is a rival answer to the same question rather than a
stage of this one. The window greys **Settings > Palette...** out to say so. It is also **NTSC
only** -- `--filter ntsc` with a PAL machine is exit 2, and the menu greys itself out -- because the
2C07 runs ten samples to a pixel and alternates its burst phase every line, so it needs its own
decoder rather than this one with different numbers in it.

**How soft it draws is a setting, and the default is not the sharpest.** Keeping the subcarrier out
of luma is what costs the picture its detail: the decoder does it by averaging a whole colour cycle,
which nulls the subcarrier exactly and takes every luma detail finer than that cycle down with it.
A television with a resonant trap in it did not pay that price, and the difference between that
average and one twice as wide is the band the blunt one threw away -- so `--filter ntsc=low`,
`=medium` and `=strong` say how much of it to add back, `strong` being the bare average and the
softest of the three. A white pixel on black keeps 60% of its light where it was put at `strong`,
72% at `medium` and 81% at `low`. **Nothing else moves**: both windows are a whole number of colour
cycles wide, so both null the subcarrier and both have a gain of one at DC, which is why a flat
field comes out flat and every colour lands where it did at all three. `--filter none=low` is exit 2
rather than a setting that quietly does nothing.

**It is not on the comparability checklist**, and the video filters are the only things in this file
that are not. Everything the report measures -- the hash, `uniqueColours`, `topColours`, `blank`,
`frameChanges` -- is taken over the colour *indices* the chip emits, so `video.finalFrame` is
identical with the filter on and off and only the PNGs differ. Which is also why it rides freely with `--play`, where
`--hack overclock` cannot: a replay does not depend on it. `video.filter` and `video.filterStrength`
in the report say which were used -- the second explicitly null when the palette drew -- and
`filter ntsc low` / `filter ntsc` / `filter none` / bare `filter` do it inside an interactive
session, which is how to take the same frame twice and diff the two pictures. A strength said there
outlasts a switch to the palette and back, since going through the palette is how the two pictures
get taken.

It costs about 2.2ms a frame against an emulated frame's 3.7ms, so it roughly halves the headroom
rather than the frame rate. `NTSCFilter` is where all of it lives, and the constant most likely to
be argued with is `DISTORTION`: the 2C02's output impedance depends on the level it is driving,
which rotates a colour's hue with its row, and NESdev's figure for a 2C02G is twice the one used
here. The 2C02E's figure is used because it is measurable which one is right for this emulator --
at 1.5e-8 the decoded hues land within a degree or two of every palette in `/palettes`, and at 3e-8
they sit ten to thirteen degrees off all of them. `NTSCFilterTests` holds that against the default
palette, greys to within 8 of 441 and everything else to within 40 -- at every strength, since a
sharpener that had reached into the chroma path is exactly what that would catch.

### Putting it on the screen a television had

`--filter crt` is the other half of the same television and the other kind of answer: the palette is
consulted exactly as `--filter none` consults it, and what changes is where the light lands. A NES
sends 240 lines and never interlaces into a set built to draw 480, so the beam lays one down, skips
the position the other field would have used, and lays down the next -- the gaps are half of the
screen rather than a defect of the picture. `--warp` adds the bow the glass gave it, which cuts the
corners off; it is refused beside any other filter, since there is no glass in front of a lookup
table. **Both work on either console**, unlike the decoder, because neither is the 2C02's.

**It needs somewhere to put a scanline, so `--filter crt` under `--scale 2` is exit 2.** One row per
line leaves nowhere for the gap, and a filter switched on and invisible is the thing `--filter
none=low` is refused to avoid. The window cannot refuse -- 1x is a size a corner gets dragged
through -- so there the mask fades out between 2x and 1x instead and comes back on the way up.
`filter crt` inside an interactive session is refused the same way, since a session's magnification
was fixed at `--scale` before it started, and `warp on|off` is a command of its own beside it.

**Neither is on the checklist either**, for the reason above: `video.finalFrame` is byte-identical
with them on and off and only the PNGs differ, and both ride freely with `--play`. `video.warp`
joins `video.filter` and `video.filterStrength` in saying what drew, explicitly null when the tube
did not.

`CRTScreen` is where all of it lives, and the whole of the arithmetic is one antiderivative: the
raster's profile is `0.5 - 0.5 sin(2*pi*u)` across a line, and every row of the picture takes the
integral over its own slice of that rather than a sample of it -- which is what lets the window
magnify by 2.37 without the mask breaking into moiré. **The beam sits a quarter of the way down a
line's share of the raster rather than halfway**, which is the one decision in there that is not
arithmetic: a gap centred on the boundary between two lines is the prettier model and any even
magnification splits it exactly in half, so 2x -- the commonest there is -- would come out with two
identical rows and no scanline anywhere.

Magnifying, masking and bending are one pass, and the obvious arrangement is three. Bending a
picture that has already been masked resamples a pattern that repeats every two rows, which is where
a scanline filter picks up its moiré; masking one that has already been bent lays straight scanlines
across a curved raster. Doing all three at once dodges both. It costs about 2.1ms a frame flat and
6ms bent, at 4x, against an emulated frame's 3.7ms.

`--filter crt=low|medium|strong` says how dark the gaps go, out of the same `FilterStrength` the
decoder reads for its own purposes -- one enum because it is one question, and because a second one
with the same three ids in it would be three chances for a command line, a config file and a report
to disagree about which of them a word meant. At two rows to a line a white picture comes out with
its lit rows at 95% of their light and its dark ones at 75% for `low`, 90% and 55% for `medium`, and
85% and 30% for `strong`. **The picture is dimmer with it on and no gain is put back**: half the
raster is unlit and the light really is gone, which is why every one of these televisions was
brighter than its picture.

There is no shadow mask, and that is deliberate rather than pending. A consumer tube's triad pitch is
finer than an NES pixel is wide at any magnification anybody uses, so drawing one triad per pixel is
not the tube's mask -- it is a different, coarser thing invented for the screenshot.

### Taking a voice out of the mixer

`--mute triangle` keeps one of the APU's five voices out of the sum, and `--mute pulse1,dmc` keeps
two. The names are `pulse1`, `pulse2`, `triangle`, `noise` and `dmc`, the flag is repeatable, and an
unknown name is exit 2 rather than a run where the voice somebody wanted silenced was still playing.

**Nothing the game can observe changes**, which is what separates this from a hack. It happens at
the mixer: the timers, the sequencers, the envelopes and the length counters all run, $4015 answers
exactly what it would have, the frame counter's and the DMC's interrupts still arrive, and the DMC
still stops the CPU to fetch its bytes. A muted run's picture, cycle counts and everything under
`run` are the unmuted run's, byte for byte.

What it does change is every number under `audio` -- the peak, the RMS, the silent frame count and
the WAV -- so **`audio.muted` is on the comparability checklist**, and it is the one thing on that
list that is not under `run`. It is there because it belongs beside the numbers it moves. It is
always present, empty when nobody muted anything, and read off the machine at the end of the run
rather than off the command line, so a session that switched one half way through is reported as it
ended.

It is not in a movie and does not refuse `--play`, for the reason `--hack unlimited-sprites` does
not: a replay does not depend on which voices anybody could hear. `mute NAME on|off` does it inside
an interactive session and a bare `mute` lists what is off, which is how to ask the same bar twice
and diff the two answers:

```sh
printf 'run 300\naudio\nmute triangle on\nrun 60\naudio\nquit\n' \
  | java -jar $JAR --headless --rom ROM.nes --interactive
```

Do not go looking for a game to see it on: nestest never touches the APU, and the power-on step on
the triangle's ladder is the whole of its sound -- so `--mute triangle` takes a run of it from a peak
of 0.20 to silence, which is what `HeadlessRunTests` asserts.

The window has the same five under **Debug > Sound Channels**, ticked when audible, beside Show
Background and Show Sprites rather than anywhere near Mute. That is the distinction worth keeping:
Mute and **Machine > Volume** decide how loudly the machine is played and these decide what it is
playing.

### The sound card is not the same clock

Nothing above applies to the window's sound output, which has a problem the headless mode does not
have: the APU makes 44100 samples per second of *emulated* time and the card plays 44100 per second
of *its own crystal's* time. The loop paces the first against the host's monotonic clock, so what is
left over is the difference between that clock and the card's -- tens of parts per million, which
at 50ppm is a whole sixty millisecond cushion gained or lost inside twenty minutes. Either way it is
a click.

So `AudioOutput` measures how full the card is every frame and resamples by up to **half a percent**
to steer it back to the middle. Three things about that are worth knowing before touching it:

- **The blocking write is no longer the pacing.** It used to be, and two things pacing one loop is
  what the drift was made of; the loop's own deadline paces now and the write is the backstop, which
  the rate control is what stops from ever being reached.
- **The card is opened at twice the wanted latency and held at half of it**, so there is always a
  whole latency's worth of room above the target and a whole one below it.
- **Half a percent is chosen for being small**, not as a compromise. It is eight cents of pitch and a
  hundred times the authority a real crystal pair needs, so a mixer that reports its fill level badly
  costs half a percent of pitch rather than a loop that hunts audibly. The approach is exponential
  with a twelve second time constant, which is why the queue is primed with silence at `open` and at
  every `flush` rather than left for the control to fill.

`audio.latency-ms` in `~/.mynes/config.properties` is how much to hold, 20 to 200 and 60 by default.
It has no menu item -- it is the question "how much delay will you trade for how much robustness",
which is not one to answer by trying five items until the clicking stops -- so the file is where it
is set and the status bar's tooltip is where it is read back. `rewind.seconds` is the only other
setting like that.

`Resampler` is the half a percent, kept apart from `AudioOutput` because it has state that has to
survive a frame boundary: the output grid's position and the sample before the frame. A resampler
that started afresh each frame would pass every test about how many samples come out and put a buzz
at sixty hertz in anyway, which is what `ResamplerTests` holds it to.

**Machine > Volume** is the other half of the same class: five steps, squared on their way to the
amplitude, because a fader that moved it linearly would do all of its audible work in the top tenth
of its travel. There is no zero -- Mute already is one, and it remembers the volume to come back to.

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
`run.hacks`, `run.genie` and `audio.muted`. Both are always present with explicit nulls. A run that started at power
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
  mynes/debug/        the disassembler, the breakpoints and their conditions, and the tracer,
                      all shared by the window and the REPL
  mynes/cheat/        Game Genie codes, and the device MMU asks on every read of PRG ROM
  mynes/video/        colour indices to pixels: the overscan crop, the frame renderer, the NTSC
                      filter that decodes the signal instead of reading a palette, and the tube
                      that lays the answer down between the lines of a raster
  mynes/palette/      the measured RGB tables, and the loader that reads them out of /palettes

mynes-patch/          depends on nothing either, core included
  mynes/patch/        IPS patches, applied to a byte[] before anyone reads it as a cartridge

mynes-headless/       depends on core and patch
  mynes/headless/     the command line mode

mynes-desktop/        depends on core, patch and headless; FlatLaf and MigLayout live here
  mynes/ui/           the Swing window, Main, the key bindings, the CHR viewer, the debugger, and
                      the sound card: the line, the volume, and the half a percent of resampling
                      that holds its queue where it was put
  mynes/ui/ppuviewer/ the two windows over what the PPU is drawing from: the four nametables with
                      the scroll window over them, and the sixty four sprites with their attributes
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

The two PPU viewers are their own package rather than beside the CHR viewer because that one is
about the tiles a game *has* and these are about where it has *put* them -- and they share a tile
decoder with each other rather than with it, since that one reads the mapper directly and these read
the PPU's own bus so that a bank switch moves them.

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
