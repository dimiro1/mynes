package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.cheat.InvalidGameGenieCodeException;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.CRTScreen;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import com.github.dimiro1.mynes.video.FrameRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the command line asked for.
 *
 * @param rom              the cartridge to run, or the zip a collection ships it in.
 * @param entry            which file inside that zip is the cartridge, or null to let the one that
 *                         is named like one be it. Meaningless, and refused, for a ROM that is not
 *                         a zip.
 * @param patches          IPS patches to apply to it, in the order they were named, before it is
 *                         read as a cartridge at all. Applied to what came out of the zip, which
 *                         is the only thing an offset in a patch could be counted from.
 * @param frames           how many frames to run, when nothing stops it sooner.
 * @param framesSet        whether {@code --frames} was actually given. {@code --play} runs the
 *                         movie's own length unless somebody named one, and how long a movie is
 *                         cannot be known while the command line is still being read.
 * @param timeout          how much real time to allow.
 * @param resetAt          frames to press the console's Reset button at the start of.
 * @param input            what to press, and when.
 * @param pressFrames      how long a press is held.
 * @param outDir           where artifacts go.
 * @param reportPath       where the report goes, or null to only print it.
 * @param quiet            whether to keep the report off standard output.
 * @param screenshotFrames frames to photograph.
 * @param screenshotLast   whether to photograph whichever frame the run ends on.
 * @param screenshotEvery  photograph every this many frames, or 0 for none.
 * @param scale            how many times to magnify a screenshot.
 * @param fullFrame        whether to keep the scanlines a television hides.
 * @param region           which machine to run the cartridge on, or null to believe its header.
 * @param filter           how a frame becomes a picture: through the palette, by decoding the
 *                         composite signal, or through the palette and onto a tube. The decoder is
 *                         NTSC only, which {@code Headless} refuses once the cartridge has said
 *                         which machine it wants; the tube is neither console's.
 * @param strength         how hard that filter is applied. Always answered, and meaningless when
 *                         {@code filter} is the bare palette.
 * @param warp             whether the tube's glass is curved. Always answered, and meaningless
 *                         unless {@code filter} is the tube.
 * @param palette          which measurement of the chip's colours to draw with, or null to let the
 *                         region decide.
 * @param audio            whether to write the sound to a file as well as counting it.
 * @param mute             which of the APU's five voices to keep out of the mixer. Not a hack and
 *                         not a change to the machine -- see {@link APUChannel} -- but it does
 *                         change every number under {@code audio} in the report, which is why
 *                         {@code audio.muted} is what tells a muted run from a plain one.
 * @param hacks            which of the things the hardware does not do to switch on.
 * @param overclock        how many idle scanlines a frame to add, which is the one hack that takes
 *                         a number rather than a yes. {@link Overclock#NONE} unless one was named.
 * @param genie            Game Genie codes to put in the cartridge slot, already decoded.
 * @param dumps            which memories to write out when the run ends.
 * @param loadState        a save state to start from instead of power on, or null.
 * @param saveState        where to write a save state when the run ends, or null.
 * @param sramIn           a battery file to fill the cartridge's RAM from before starting, or null.
 * @param sramOut          where to write that RAM when the run ends, or null.
 * @param record           where to write a movie of the run, or null.
 * @param play             a movie to play instead of a schedule, or null. It carries the input, the
 *                         resets, the codes and where the run starts, so it refuses the flags that
 *                         would say those things twice.
 * @param expectNotBlank   the final picture must show more than one colour.
 * @param expectAudio      some sample must not have been silence.
 * @param expectMotion     at least this many frames must have differed from the one before, or -1.
 * @param interactive      whether to take commands instead of running a schedule.
 * @param scriptPath       where the interactive mode reads commands from, or null for stdin.
 * @param format           how the interactive mode spells its replies: readable text, compact
 *                         JSON, or {@code AUTO} to decide by whether a person is at the terminal.
 * @param help             whether to print the usage and stop.
 * @param listPalettes     whether to print the palettes and stop.
 */
public record Options(
        Path rom,
        String entry,
        List<Path> patches,
        long frames,
        boolean framesSet,
        Duration timeout,
        List<Long> resetAt,
        InputSchedule input,
        int pressFrames,
        Path outDir,
        Path reportPath,
        boolean quiet,
        Set<Long> screenshotFrames,
        boolean screenshotLast,
        long screenshotEvery,
        int scale,
        boolean fullFrame,
        Region region,
        NESPalette palette,
        VideoFilter filter,
        FilterStrength strength,
        boolean warp,
        boolean audio,
        Set<APUChannel> mute,
        Set<String> hacks,
        Overclock overclock,
        List<GameGenieCode> genie,
        List<String> dumps,
        Path loadState,
        Path saveState,
        Path sramIn,
        Path sramOut,
        Path record,
        Path play,
        boolean expectNotBlank,
        boolean expectAudio,
        long expectMotion,
        boolean interactive,
        Path scriptPath,
        Format format,
        boolean help,
        boolean listPalettes) {

    /**
     * How the interactive REPL spells each reply.
     * <p>
     * {@code AUTO} is resolved once, at startup, to {@code TEXT} for a person at a terminal and
     * {@code JSON} for anything piped or scripted; the other two are a person overruling that.
     */
    public enum Format {
        AUTO, JSON, TEXT
    }

    /**
     * Drawing the sprites the eight output units had no room for, so a scanline holding more than
     * eight of them stops flickering. Not hardware, which is the point of the flag: a run with this
     * on and a run without it are two different machines, and {@code run.hacks} in the report says
     * which one happened.
     */
    public static final String UNLIMITED_SPRITES = "unlimited-sprites";

    /**
     * Extra idle scanlines a frame, so that a game whose main loop overruns stops dropping frames.
     * The one hack that takes a number: {@code --hack overclock=131}, or {@code =131+20} to put some
     * of them after the NMI as well.
     * <p>
     * Unlike {@link #UNLIMITED_SPRITES} this one changes the machine's timing and so what the game
     * does, which is why {@code --play} refuses it and a movie carries its own.
     */
    public static final String OVERCLOCK = "overclock";

    /**
     * Every hack there is, which is also what an unknown {@code --hack} is answered with.
     */
    public static final Set<String> HACKS = Set.of(UNLIMITED_SPRITES, OVERCLOCK);

    /**
     * Ten seconds of emulated time, which is about a second of real time and long enough for most
     * cartridges to have drawn something.
     */
    private static final long DEFAULT_FRAMES = 600;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /**
     * One frame is enough for a game that reads the pad in its NMI handler. Two is enough for one
     * that does not.
     */
    private static final int DEFAULT_PRESS_FRAMES = 2;

    /**
     * Under {@code target} because git already ignores it and {@code mvn clean} already sweeps it,
     * so a run leaves nothing behind that anybody has to remember to tidy up.
     */
    private static final Path DEFAULT_OUT = Path.of("target", "headless");

    private static final String REPORT_NAME = "report.json";

    /**
     * When {@code --report} is given this instead of a path, the report is printed and not filed.
     */
    private static final String STDOUT = "-";

    private static final String USAGE = """
            MyNES headless -- runs a ROM with no display and writes down what happened.

            Usage:
              mvn -q compile exec:exec@headless -Dmynes.args="--rom FILE [options]"
              java -jar target/mynes.jar --headless --rom FILE [options]

            The second is worth building once (mvn -B package -DskipTests) for anything run more
            than a few times: Maven takes a couple of seconds to get going and the jar takes a third
            of one.

            The machine is deterministic. The same ROM, the same input and the same frame count
            produce byte-identical artifacts on every run and on every computer, which is what makes
            a frame hash worth writing down and two reports worth diffing.

            Cartridge and length
              --rom FILE            The .nes file to run, or a .zip holding one -- which is how
                                    nearly every collection ships them. Required. A zip is opened
                                    in memory and nothing is unpacked to disk. Recognised by what
                                    is in the file rather than by its name.
              --entry NAME          Which file inside that zip is the cartridge. Only needed for a
                                    zip holding more than one, which is refused without it rather
                                    than guessed at. Either the whole name the zip stores or just
                                    the file name at the end of it; whichever file ran is written
                                    into the report as cart.entry either way.
              --patch FILE          Apply an IPS patch to it before running it, which is how a
                                    romhack is handed out. Repeatable, and applied in the order
                                    given, to whatever came out of the zip when there was one.
                                    Nothing is written back: the file on disk is left exactly as it
                                    was, and only the copy in memory is patched. The cart.sha256 in
                                    the report is the digest of the patched image, since that is
                                    what actually ran.
              --frames N            Stop after N completed frames. Default 600, which is ten
                                    seconds of emulated time and about a second of real time.
              --timeout SECONDS     Give up after this much real time and write what there is so
                                    far. Default 120.
              --reset-at N          Press the console Reset button at the start of frame N.
                                    Repeatable.
              --region ntsc|pal     Which machine to run it on. Left off, the cartridge's header
                                    decides -- and since nearly every dump leaves that field at
                                    zero, that means NTSC. A PAL machine has 312 scanlines instead
                                    of 262, 3.2 dots to a CPU cycle instead of 3, and 50.0070
                                    frames a second instead of 60.0988, so the two are not
                                    comparable runs: the report's run.region says which happened.

            Input
              --input SPEC[,SPEC..] What to press, and when. Repeatable. Frames count from power on.
                                      N:BUTTONS      press on frame N
                                      N-M:BUTTONS    hold from frame N until frame M, M excluded
                                      N/S:BUTTONS    press on N, then every S frames after it
                                      N/SxC:BUTTONS  the same, but only C times
                                    BUTTONS is one or more of a, b, select, start, up, down, left
                                    and right, joined with +.
                                      --input 60:start
                                      --input 200-400:right
                                      --input 60/40:start,300:a+right
                                    Left alone, Super Mario Bros., Super Mario Bros. 3 and Tetris
                                    sit on their title screens forever and make no sound; only Super
                                    Mario Bros. 2 plays untouched. 60/40:start gets all four past
                                    their menus.
              --input-file FILE     The same, one SPEC per line. Blank lines and lines starting with
                                    # are ignored.
              --press-frames N      How long a press from N: or N/S: is held, in frames. Default 2.

            Output
              --out DIR             Where artifacts go. Default target/headless, which git already
                                    ignores and mvn clean sweeps. Nothing already there is deleted;
                                    the report lists what this run wrote.
              --report FILE         Where the JSON report goes. Default <out>/report.json. "-"
                                    writes it to standard output and to no file.
              --quiet               Do not also print the report to standard output.

            Picture
              --screenshot LIST     Frames to photograph, comma separated. "last" is whichever frame
                                    the run ended on. Repeatable.
                                      --screenshot 60,300,last
              --screenshot-every N  A screenshot every N frames.
              --scale N             Magnify the picture N times, 1 to 8. Default 1, which is 256x224.
              --full-frame          Write all 240 scanlines instead of the 224 a television shows.
                                    The emulator's own window hides the same eight at each end, so
                                    leaving this off is what a player would see. The frame hash
                                    always covers the cropped picture, whichever this is.
              --palette ID          Which measurement of the chip's colours to draw with. Default
                                    nesdev, or 2c07 on a PAL machine, whose PPU does not generate
                                    the same colours at all. --list-palettes has the rest.
              --list-palettes       Print the palette ids and names, then stop.
              --filter NAME         How the picture is drawn. Default none, the palette straight
                                    through. Nothing measured moves whichever is chosen -- the frame
                                    hash and the colour counts are over colour indices -- so this
                                    changes the PNGs and nothing else.
                                      ntsc   Decode the composite signal the chip drew instead of
                                             looking each pixel up in a palette: colour bleed, dot
                                             crawl and the artefact colours a palette cannot
                                             produce. The palette is not consulted while it is on,
                                             and it is refused on a PAL machine, whose signal is a
                                             different signal.
                                      crt    Look the pixel up in the palette as usual and then lay
                                             it down the way a picture tube did, with the unlit half
                                             of the raster between the lines, and --warp for the
                                             curve of the glass. Either console. Needs --scale 2 or
                                             more, since at 1x there is one row per line and nowhere
                                             to put a scanline.
                                    =low, =medium or =strong says how hard, and neither reading is
                                    the other's: for ntsc it is how much of the fine detail the
                                    chroma trap costs to give back, so strong is the plain
                                    cycle-wide average and the softest of the three; for crt it is
                                    how dark the gaps go, so strong is the most visible mask.
                                    Default medium. --filter none=low is an error rather than a
                                    setting that does nothing.
              --warp                Bend the picture the way the curve of a tube's glass bent it,
                                    which cuts the corners off. Needs --filter crt, since it is
                                    part of that filter rather than a fourth thing to choose.

            Sound
              --audio               Also write <out>/audio.wav: signed sixteen bit, one channel,
                                    44100Hz. The report's peak, RMS and silent frame counts are
                                    there either way; this only adds the file.
              --mute NAME[,NAME..]  Keep a voice out of the mixer: pulse1, pulse2, triangle, noise
                                    or dmc. Repeatable. Nothing the game can observe changes -- the
                                    length counters still count, $4015 answers the same, and the
                                    DMC still steals its cycles -- so this is how to ask which voice
                                    a noise is coming from rather than a hack. It does change the
                                    peak, the RMS, the silent frame count and the WAV, so
                                    audio.muted in the report is what tells a muted run from a plain
                                    one.

            Hacks, which are things the console does not do
              --hack NAME[,NAME..]  Switch one on. All of them are off unless named here, and
                                    run.hacks in the report says which were on, so a run with one
                                    and a run without it can be told apart.
                                      unlimited-sprites   Draw the sprites the chip would have
                                                          dropped, so a scanline holding more than
                                                          eight of them stops flickering. Nothing a
                                                          game can see changes: the overflow flag
                                                          still rises and the cartridge sees the
                                                          same address bus.
                                      overclock=N[+M]     Give the program N extra idle scanlines a
                                                          frame before the NMI, and M after it, so
                                                          that a game whose main loop overruns its
                                                          frame stops dropping one. A line is about
                                                          113.67 CPU cycles on NTSC and 106.56 on
                                                          PAL; 0 to 1000 each, and 0 is off.
                                                          --hack overclock=131 is half an NTSC
                                                          frame again.
                                                          Unlike the one above this changes the
                                                          machine's timing and so what the game
                                                          does, which makes an overclocked run and
                                                          a plain one two different games rather
                                                          than two views of one. Reach for the
                                                          before-NMI number: extra lines after the
                                                          NMI move the picture relative to it, and
                                                          break code that counts cycles down to a
                                                          mid-screen split. The picture is drawn
                                                          exactly as the hardware draws it either
                                                          way, and the sound is a hardware frame's
                                                          worth, since the APU stands still through
                                                          the extra lines.

            Game Genie, which is a thing the console did do
              --genie CODE[,CODE..] Put a code in the cartridge slot. Repeatable. Six letters or
                                    eight, from APZLGITYEOXUKSVN -- there is no B, C, D or R in a
                                    code, whatever it looks like. Eight-letter codes carry a byte the
                                    cartridge has to answer with before they fire, which is what
                                    pins one to a single bank.
                                      --genie SXIOPO           infinite lives in Super Mario Bros.
                                      --genie IKAEAUAK,PNAEPLIV
                                    Nothing is written back and the cartridge is not modified in any
                                    way, so cart.sha256 in the report is the same with codes in as
                                    without -- unlike --patch, which really does make a different
                                    image. run.genie is the only thing that tells two such runs
                                    apart, and a save state taken from one will load into the other
                                    without complaining. The real cartridge held three codes; this
                                    holds as many as you type.

            Memory, dumped once the run has finished
              --dump LIST           Comma separated, from: ram (2KB), oam (256B), palette (32B),
                                    nametables (4KB), prgram (8KB), chr (8KB), or all. Raw binary,
                                    one file each, under <out>. Palette RAM is in the report as
                                    well, being 32 bytes.

            Saved games and save states
              --sram-in FILE        Fill the cartridge's battery RAM from FILE before the run. Raw
                                    8KB of $6000-$7FFF, which is what every other emulator writes,
                                    so a save from one of them can be handed straight over. A
                                    shorter file fills what it can and a longer one is cut.
              --sram-out FILE       Write that RAM out when the run ends. Unlike the window, this
                                    does not ask the cartridge whether it has a battery fitted: the
                                    flag is the answer. Taken from the chip rather than read through
                                    the bus, so a game that has switched the chip off still saves.
              --load-state FILE     Start from a save state instead of from power on. It has to have
                                    been taken from this exact ROM, and a run that starts this way
                                    is not comparable with one that starts at power on -- the
                                    report's run.state says which happened.
              --save-state FILE     Write a save state when the run ends. Applied after --sram-in,
                                    so a state's own copy of the cartridge RAM wins.

            Movies, which are sessions rather than snapshots
              --record FILE         Write a .mnm movie of this run: where it started, one button
                                    mask per finished frame, and the frames Reset was pressed at.
                                    Combines with everything, including --interactive.
                                    A run that started at power on records a movie that starts
                                    there and carries no state at all. Anything else -- a
                                    --load-state, a --sram-in, or a rewind that went back past the
                                    start of the recording -- puts a save state in the file to
                                    start from, since there is otherwise nothing to say where the
                                    beginning was.
                                    Rewinding while recording drops the frames that were taken
                                    back, so a movie holds the timeline that was finally played and
                                    a replay never re-enacts the revert.
              --play FILE           Play one instead of running a schedule. --frames defaults to
                                    the movie's own length; asking for more runs on past the end
                                    with nothing held down, which is how to see what the game does
                                    when the player stops playing.
                                    The movie is the input, so --play refuses --record, --input,
                                    --input-file, --reset-at, --genie, --hack overclock,
                                    --load-state, --sram-in and --interactive rather than letting
                                    one of them quietly win. --hack unlimited-sprites still
                                    combines with it, being a change to the picture and to nothing
                                    the replay depends on.
                                    It has to be the same cartridge and the same region; anything
                                    else exits 2. run.replay in the report says what was played.

            Expectations. Each one that fails makes the run exit 4; the report says which. Anything
            more particular than these belongs in jq over the report.
              --expect-not-blank    The final picture must show more than one colour.
              --expect-audio        Some sample must not have been silence.
              --expect-motion N     At least N frames must differ from the frame before them.

            Interactive
              --interactive         Take commands on standard input instead of running a schedule,
                                    and answer each one with a line of JSON. "help" lists them.
                                    A whole session can be piped in at once.
              --script FILE         Read those commands from a file instead of standard input.
              --format auto|json|text
                                    How to spell each reply. auto (the default) is readable text at
                                    a terminal and compact JSON when piped or scripted; text and
                                    json force one regardless.

            Exit codes
              0  the run finished           3  --timeout ran out
              1  something else went wrong  4  an expectation failed
              2  the command line was wrong 5  the ROM would not load
            """;

    /**
     * The usage text, which is also what {@code --help} prints.
     */
    public static String usage() {
        return USAGE;
    }

    /**
     * Reads a command line.
     *
     * @throws UsageException if it cannot be read. The message is meant to be printed on its own.
     */
    public static Options parse(final String[] args) {
        Path rom = null;
        String entry = null;
        var patches = new ArrayList<Path>();
        var frames = DEFAULT_FRAMES;
        var framesSet = false;
        var timeout = DEFAULT_TIMEOUT;
        var resetAt = new ArrayList<Long>();
        var inputSpecs = new ArrayList<String>();
        var pressFrames = DEFAULT_PRESS_FRAMES;
        var outDir = DEFAULT_OUT;
        String reportPath = null;
        var quiet = false;
        var screenshotFrames = new TreeSet<Long>();
        var screenshotLast = false;
        var screenshotEvery = 0L;
        var scale = 1;
        var fullFrame = false;
        Region region = null;
        NESPalette palette = null;
        var filter = VideoFilter.NONE;
        var strength = FilterStrength.defaultStrength();
        var warp = false;
        var audio = false;
        var mute = EnumSet.noneOf(APUChannel.class);
        var hacks = new LinkedHashSet<String>();
        var overclock = Overclock.NONE;
        var genie = new ArrayList<GameGenieCode>();
        var dumps = new LinkedHashSet<String>();
        Path loadState = null;
        Path saveState = null;
        Path sramIn = null;
        Path sramOut = null;
        Path record = null;
        Path play = null;
        var expectNotBlank = false;
        var expectAudio = false;
        var expectMotion = -1L;
        var interactive = false;
        Path scriptPath = null;
        var format = Format.AUTO;
        var help = false;
        var listPalettes = false;

        for (var i = 0; i < args.length; i++) {
            var flag = args[i];

            switch (flag) {
                case "--help", "-h" -> help = true;
                case "--list-palettes" -> listPalettes = true;
                case "--rom" -> rom = Path.of(value(args, ++i, flag));
                case "--entry" -> entry = value(args, ++i, flag);
                case "--patch" -> patches.add(Path.of(value(args, ++i, flag)));
                case "--frames" -> {
                    frames = positive(value(args, ++i, flag), flag);
                    framesSet = true;
                }
                case "--timeout" -> timeout = Duration.ofSeconds(
                        positive(value(args, ++i, flag), flag));
                case "--reset-at" -> resetAt.add(positive(value(args, ++i, flag), flag));
                case "--input" -> inputSpecs.add(value(args, ++i, flag));
                case "--input-file" -> inputSpecs.addAll(readInputFile(value(args, ++i, flag)));
                case "--press-frames" -> pressFrames =
                        (int) positive(value(args, ++i, flag), flag);
                case "--out" -> outDir = Path.of(value(args, ++i, flag));
                case "--report" -> reportPath = value(args, ++i, flag);
                case "--quiet" -> quiet = true;
                case "--screenshot" -> screenshotLast |=
                        parseScreenshots(value(args, ++i, flag), screenshotFrames);
                case "--screenshot-every" -> screenshotEvery =
                        positive(value(args, ++i, flag), flag);
                case "--scale" -> scale = parseScale(value(args, ++i, flag));
                case "--full-frame" -> fullFrame = true;
                case "--region" -> region = parseRegion(value(args, ++i, flag));
                case "--palette" -> palette = parsePalette(value(args, ++i, flag));
                case "--filter" -> {
                    var spec = value(args, ++i, flag);
                    var equals = spec.indexOf('=');

                    filter = parseFilter(equals < 0 ? spec : spec.substring(0, equals));
                    strength = equals < 0
                            ? FilterStrength.defaultStrength()
                            : parseFilterStrength(filter, spec.substring(equals + 1));
                }
                case "--warp" -> warp = true;
                case "--audio" -> audio = true;
                case "--mute" -> parseMute(value(args, ++i, flag), mute);
                case "--hack" -> overclock =
                        parseHacks(value(args, ++i, flag), hacks, overclock);
                case "--genie" -> parseGenie(value(args, ++i, flag), genie);
                case "--dump" -> parseDumps(value(args, ++i, flag), dumps);
                case "--load-state" -> loadState = Path.of(value(args, ++i, flag));
                case "--save-state" -> saveState = Path.of(value(args, ++i, flag));
                case "--sram-in" -> sramIn = Path.of(value(args, ++i, flag));
                case "--sram-out" -> sramOut = Path.of(value(args, ++i, flag));
                case "--record" -> record = Path.of(value(args, ++i, flag));
                case "--play" -> play = Path.of(value(args, ++i, flag));
                case "--expect-not-blank" -> expectNotBlank = true;
                case "--expect-audio" -> expectAudio = true;
                case "--expect-motion" -> expectMotion = positive(value(args, ++i, flag), flag);
                case "--interactive" -> interactive = true;
                case "--script" -> {
                    scriptPath = Path.of(value(args, ++i, flag));
                    interactive = true;
                }
                case "--format" -> format = parseFormat(value(args, ++i, flag));
                default -> throw new UsageException(
                        "\"" + flag + "\" is not an option. --help lists them.");
            }
        }

        if (help || listPalettes) {
            // Neither of these runs anything, so neither needs a cartridge.
            rom = null;
        } else if (rom == null) {
            throw new UsageException("--rom is required. --help says what else there is.");
        }

        // The one filter whose picture depends on how big the picture is. A screenshot magnified
        // once has one row per scanline and so nowhere to put the row a scanline is, and a run that
        // asked for a tube and got the plain palette back is a run nobody asked for -- the same
        // reason --filter none=low is refused rather than ignored. The window fades the mask out
        // instead, because there the magnification is a corner somebody dragged rather than a
        // number somebody typed.
        if (filter == VideoFilter.CRT && scale < CRTScreen.MINIMUM_ROWS_PER_LINE) {
            throw new UsageException("--filter crt needs --scale "
                    + CRTScreen.MINIMUM_ROWS_PER_LINE + " or more: a scanline is the row a line was"
                    + " not drawn on, and --scale " + scale + " draws one row per line.");
        }

        // Refused rather than remembered, for the reason --filter none=low is: there is no glass in
        // front of a lookup table, so this is somebody expecting a bent picture and not getting one.
        if (warp && filter != VideoFilter.CRT) {
            throw new UsageException("--warp is the curve of a picture tube's glass, and --filter "
                    + filter.id() + " does not draw on one. --filter crt does.");
        }

        if (play != null) {
            // Each of these is a second answer to a question the movie has already answered, and a
            // run that quietly took one of them would not be the recorded session at all. Refused
            // one at a time rather than as a list, so the message names the flag that was typed.
            refuseWithPlay(record != null, "--record",
                    "a movie is not something to re-record; play it and record the result some"
                            + " other way if that is really the intention");
            refuseWithPlay(!inputSpecs.isEmpty(), "--input",
                    "the movie is the input");
            refuseWithPlay(!resetAt.isEmpty(), "--reset-at",
                    "the movie carries the frames Reset was pressed at");
            refuseWithPlay(!overclock.isNone(), "--hack overclock",
                    "the movie carries the overclock it was recorded with, and running it with"
                            + " another would be a different run");
            refuseWithPlay(!genie.isEmpty(), "--genie",
                    "the movie carries the codes it was recorded with, and putting others in would"
                            + " be a different run");
            refuseWithPlay(loadState != null, "--load-state",
                    "the movie says where it starts, at power on or from a state inside it");
            refuseWithPlay(sramIn != null, "--sram-in",
                    "a movie that needed the battery filled was recorded from a state that already"
                            + " has it");
            refuseWithPlay(interactive, "--interactive",
                    "a replay is a run of a schedule that is already written down");
        }

        var report = STDOUT.equals(reportPath) ? null
                : reportPath == null ? outDir.resolve(REPORT_NAME) : Path.of(reportPath);

        return new Options(
                rom,
                entry,
                List.copyOf(patches),
                frames,
                framesSet,
                timeout,
                List.copyOf(resetAt),
                InputSchedule.parse(inputSpecs, pressFrames),
                pressFrames,
                outDir,
                report,
                quiet,
                Set.copyOf(screenshotFrames),
                screenshotLast,
                screenshotEvery,
                scale,
                fullFrame,
                region,
                palette,
                filter,
                strength,
                warp,
                audio,
                Set.copyOf(mute),
                Set.copyOf(hacks),
                overclock,
                List.copyOf(genie),
                List.copyOf(dumps),
                loadState,
                saveState,
                sramIn,
                sramOut,
                record,
                play,
                expectNotBlank,
                expectAudio,
                expectMotion,
                interactive,
                scriptPath,
                format,
                help,
                listPalettes);
    }

    /**
     * Which machine to run this cartridge on: the one asked for, or the one it asks for.
     */
    public Region regionFor(final Cart cart) {
        return region == null ? cart.region() : region;
    }

    /**
     * Which palette to draw with on that machine.
     * <p>
     * The region has a say because the two PPUs do not make the same colours -- an NTSC table on a
     * PAL game is wrong rather than merely a different measurement -- but only when nobody has
     * named one, since an explicit {@code --palette} is somebody who knows what they want.
     */
    public NESPalette paletteFor(final Region region) {
        return palette == null ? Palettes.defaultPalette(region) : palette;
    }

    /**
     * Where the sound goes when {@code --audio} was asked for.
     */
    public Path wavPath() {
        return outDir.resolve("audio.wav");
    }

    /**
     * Where the screenshot of a given frame goes. Zero padded so that the files sort into the order
     * the frames happened in.
     */
    public Path screenshotPath(final long frame) {
        return outDir.resolve(String.format("frame-%06d.png", frame));
    }

    /**
     * The pattern {@link #screenshotPath} follows, for a report that has to say where to look.
     */
    public String screenshotPattern() {
        return outDir.resolve("frame-%06d.png").toString();
    }

    public Path dumpPath(final String what) {
        return outDir.resolve(what + ".bin");
    }

    /**
     * Whether a frame is one of the ones asked for by number.
     * <p>
     * {@code last} is not answered here, because which frame that is depends on how the run ended:
     * a timeout stops short of {@link #frames()} and the last picture is still worth having.
     */
    public boolean wantsScreenshotAt(final long frame) {
        return screenshotFrames.contains(frame)
                || (screenshotEvery > 0 && frame % screenshotEvery == 0);
    }

    /**
     * Refuses one of the flags {@code --play} replaces, saying which and why.
     * <p>
     * Refused rather than ignored, and rather than allowed to win: a replay whose input came from
     * somewhere other than the movie is not a replay of anything, and it would look exactly like one
     * that worked.
     */
    private static void refuseWithPlay(
            final boolean given, final String flag, final String because) {
        if (given) {
            throw new UsageException(
                    "--play and " + flag + " cannot both be given: " + because + ".");
        }
    }

    private static String value(final String[] args, final int i, final String flag) {
        if (i >= args.length) {
            throw new UsageException(flag + " wants a value after it.");
        }

        return args[i];
    }

    private static long positive(final String text, final String flag) {
        try {
            var value = Long.parseLong(text);

            if (value < 0) {
                throw new UsageException(flag + " cannot be negative, and " + value + " is.");
            }

            return value;
        } catch (NumberFormatException e) {
            throw new UsageException(flag + " wants a number, not \"" + text + "\".");
        }
    }

    private static Format parseFormat(final String text) {
        return switch (text.toLowerCase()) {
            case "auto" -> Format.AUTO;
            case "json" -> Format.JSON;
            case "text" -> Format.TEXT;
            default -> throw new UsageException(
                    "--format is auto, json or text, not \"" + text + "\".");
        };
    }

    private static int parseScale(final String text) {
        try {
            var scale = Integer.parseInt(text);

            if (scale < 1 || scale > FrameRenderer.MAX_SCALE) {
                throw new UsageException(
                        "--scale is 1 to " + FrameRenderer.MAX_SCALE + ", not " + scale + ".");
            }

            return scale;
        } catch (NumberFormatException e) {
            throw new UsageException("--scale wants a number, not \"" + text + "\".");
        }
    }

    /**
     * Reads a screenshot list, adding the frames to {@code frames}.
     *
     * @return whether the list held "last".
     */
    private static boolean parseScreenshots(final String text, final TreeSet<Long> frames) {
        var last = false;

        for (var token : text.split(",")) {
            var trimmed = token.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            if ("last".equalsIgnoreCase(trimmed)) {
                last = true;
                continue;
            }

            try {
                frames.add(Long.parseLong(trimmed));
            } catch (NumberFormatException e) {
                throw new UsageException(
                        "--screenshot takes frame numbers and \"last\", not \"" + trimmed + "\".");
            }
        }

        return last;
    }

    /**
     * Reads a channel list, adding what it names to {@code mute}.
     * <p>
     * An unknown name is refused rather than stepped over, for the reason {@link #parseHacks}
     * refuses one: a run where the voice somebody wanted silenced was still playing would look
     * exactly like a run where it had nothing to play, and the whole use of this flag is telling
     * those two apart.
     */
    private static void parseMute(final String text, final Set<APUChannel> mute) {
        for (var name : text.split(",", -1)) {
            var channel = APUChannel.byId(name);

            if (channel == null) {
                throw new UsageException(
                        "--mute does not know \"" + name.trim() + "\". It knows "
                                + APUChannel.ids() + ".");
            }

            mute.add(channel);
        }
    }

    /**
     * Reads a hack list, adding what it names to {@code hacks}.
     * <p>
     * An unknown name is refused rather than ignored, for the reason a misspelled palette is: a run
     * that quietly happened without the hack somebody asked for would look like it had worked, and
     * the picture is the only place the difference shows.
     * <p>
     * One of them takes a value and the rest do not, which is why a token is split on its first
     * {@code =} before the name is looked up: {@code overclock} without a line count is a wish
     * nobody can act on, and {@code unlimited-sprites=3} is somebody with the wrong idea of what
     * it does. Both are refused by name rather than by shape.
     *
     * @param overclock what the overclock was before this list, so that several {@code --hack}
     *                  flags accumulate rather than the last one wiping the rest.
     * @return what it is after it.
     */
    private static Overclock parseHacks(
            final String text, final Set<String> hacks, final Overclock overclock) {
        var result = overclock;

        for (var token : text.split(",")) {
            var trimmed = token.trim().toLowerCase();

            if (trimmed.isEmpty()) {
                continue;
            }

            var equals = trimmed.indexOf('=');
            var name = equals < 0 ? trimmed : trimmed.substring(0, equals);
            var value = equals < 0 ? null : trimmed.substring(equals + 1);

            if (!HACKS.contains(name)) {
                throw new UsageException(
                        "--hack does not know \"" + name + "\". It knows "
                                + String.join(", ", new TreeSet<>(HACKS)) + ".");
            }

            if (OVERCLOCK.equals(name)) {
                if (value == null) {
                    throw new UsageException(
                            "--hack overclock wants a number of scanlines, as in"
                                    + " \"--hack overclock=131\", or \"=131+20\" to put some of"
                                    + " them after the NMI as well.");
                }

                result = parseOverclock(value);
            } else if (value != null) {
                throw new UsageException(
                        "--hack " + name + " is switched on by naming it and takes no value, so"
                                + " \"=" + value + "\" is not something it can do.");
            }

            hacks.add(name);
        }

        return result;
    }

    /**
     * Reads {@code LINES} or {@code LINES+MORE}: how many idle scanlines to add before the NMI, and
     * how many after it.
     * <p>
     * Two numbers rather than one because they are not interchangeable -- extra post-render lines
     * change nothing a game can observe, where extra vblank lines move the picture relative to the
     * NMI -- and the shorter form is the one to reach for. {@code 0} is the hardware, and is how to
     * write "off" in a script that builds its own command line.
     */
    private static Overclock parseOverclock(final String text) {
        var plus = text.indexOf('+');
        var before = plus < 0 ? text : text.substring(0, plus);
        var after = plus < 0 ? "0" : text.substring(plus + 1);

        try {
            return new Overclock(scanlines(before), scanlines(after));
        } catch (IllegalArgumentException e) {
            throw new UsageException("--hack overclock: " + e.getMessage());
        }
    }

    private static int scanlines(final String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new UsageException(
                    "--hack overclock wants a number of scanlines, not \"" + text + "\".");
        }
    }

    /**
     * Reads a list of Game Genie codes, decoding each one as it goes.
     * <p>
     * Decoded here rather than when the machine is built, so that a misspelled code is a bad command
     * line and exits 2. The argument {@link #parseHacks} makes applies with more force to these: a
     * hack is one of two names and a code is eight letters from an alphabet with no B, C, D or R in
     * it, so getting one wrong is not a remote possibility -- and a run that quietly happened without
     * the cheat somebody asked for looks exactly like one that worked.
     * <p>
     * A code that is already in the list is put in again rather than refused, because
     * {@code GameGenie.add} is the one place that decides what two codes for one address mean.
     */
    private static void parseGenie(final String text, final List<GameGenieCode> genie) {
        for (var token : text.split(",")) {
            var trimmed = token.trim();

            if (trimmed.isEmpty()) {
                continue;
            }

            try {
                genie.add(GameGenieCode.decode(trimmed));
            } catch (InvalidGameGenieCodeException e) {
                throw new UsageException("--genie " + e.getMessage());
            }
        }
    }

    private static void parseDumps(final String text, final Set<String> dumps) {
        for (var token : text.split(",")) {
            var trimmed = token.trim().toLowerCase();

            if (trimmed.isEmpty()) {
                continue;
            }

            if ("all".equals(trimmed)) {
                dumps.addAll(Session.DUMPS);
                continue;
            }

            if (!Session.DUMPS.contains(trimmed)) {
                throw new UsageException(
                        "--dump does not know \"" + trimmed + "\". It knows "
                                + String.join(", ", Session.DUMPS) + " and all.");
            }

            dumps.add(trimmed);
        }
    }

    /**
     * Finds a region by id.
     * <p>
     * Refused rather than defaulted for the reason a misspelled palette is: a run that quietly
     * happened on the other machine would look like it had worked, and every number in the report
     * would be about a console nobody asked for.
     */
    private static Region parseRegion(final String id) {
        var region = Region.byId(id);

        if (region == null) {
            throw new UsageException(
                    "--region is ntsc or pal, not \"" + id + "\". Leave it off to believe the"
                            + " cartridge's header.");
        }

        return region;
    }

    /**
     * Which filter to colour the picture with.
     * <p>
     * Refused rather than defaulted, for the reason a misspelled palette is.
     */
    private static VideoFilter parseFilter(final String name) {
        var filter = VideoFilter.byId(name);

        if (filter == null) {
            throw new UsageException(
                    "--filter is " + VideoFilter.ids() + ", not \"" + name + "\".");
        }

        return filter;
    }

    /**
     * How hard to apply it, out of the half of {@code --filter ntsc=low} after the equals sign.
     * <p>
     * Refused on the palette rather than ignored. A strength is how much of what a filter does to
     * do, and the bare palette does nothing, so {@code --filter none=low} is not a setting that
     * happens to have no effect -- it is somebody expecting a softer picture out of a lookup table,
     * and the way to find that out is to be told.
     */
    private static FilterStrength parseFilterStrength(
            final VideoFilter filter, final String name) {
        if (filter == VideoFilter.NONE) {
            throw new UsageException(
                    "--filter " + filter.id() + " takes no strength: a strength says how much of"
                            + " what a filter does to do, and " + filter.id() + " is the palette"
                            + " straight through.");
        }

        var strength = FilterStrength.byId(name);

        if (strength == null) {
            throw new UsageException("--filter " + filter.id() + "= is " + FilterStrength.ids()
                    + ", not \"" + name + "\".");
        }

        return strength;
    }

    /**
     * Finds a palette by id.
     * <p>
     * Deliberately not {@link Palettes#byId}, which logs a warning and hands back the default. That
     * is the right answer for a settings file somebody typed by hand and the wrong one here: a
     * misspelled {@code --palette} would quietly produce a picture in a palette nobody asked for,
     * and the run would look like it had worked.
     */
    private static NESPalette parsePalette(final String id) {
        for (var palette : Palettes.all()) {
            if (palette.id().equals(id)) {
                return palette;
            }
        }

        var ids = new ArrayList<String>();
        Palettes.all().forEach(palette -> ids.add(palette.id()));

        throw new UsageException(
                "\"" + id + "\" is not a palette. They are " + String.join(", ", ids) + ".");
    }

    private static List<String> readInputFile(final String path) {
        try {
            var specs = new ArrayList<String>();

            for (var line : Files.readAllLines(Path.of(path))) {
                var trimmed = line.trim();

                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    specs.add(trimmed);
                }
            }

            return specs;
        } catch (IOException e) {
            throw new UsageException(
                    "--input-file " + path + " could not be read: " + e.getMessage());
        }
    }
}
