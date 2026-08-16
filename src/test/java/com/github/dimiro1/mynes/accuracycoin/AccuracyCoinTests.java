package com.github.dimiro1.mynes.accuracycoin;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs 100thCoin's AccuracyCoin ROM and holds its 141 results against a table.
 * <p>
 * The ROM is a single NROM cartridge carrying every accuracy test its author could fit, and it
 * scores itself: <b>one byte per test in the page at $0400</b>, holding {@code (code << 2) | status}
 * where bit 0 of the status means passed, $FF means the test was marked to be skipped, and the code
 * is the error -- or, for a test with more than one acceptable answer, <em>which</em> acceptable
 * answer -- drawn on screen in base 36. {@link #EXPECTED} spells those out the same way, so a
 * failure here reads like the error code list in the ROM's own README.
 * <p>
 * This is a <b>ratchet</b>, not a pass/fail gate. Every one of the 141 is named, and a test whose
 * result disagrees with the table fails the run <em>in either direction</em>: a pass that turned
 * into a failure is a regression, and a failure that turned into a pass means the table is stale
 * and the entry has to come out of it. That second half is the point -- it is what stops the
 * expected-failure list quietly outliving the bugs it describes.
 * <p>
 * To run it by hand and read the same bytes:
 * <pre>{@code
 * java -jar target/mynes.jar --headless --rom src/test/resources/accuracycoin/AccuracyCoin.nes \
 *     --frames 4000 --input 120/10x3:start --dump ram
 * }</pre>
 * then decode {@code ram.bin} at offsets $0400-$0492. The machine is deterministic, but that command
 * is not quite this run: a handful of tests are sensitive to which cycle Start was pressed on, and
 * this presses until the ROM says the run has started rather than on a fixed frame. Expect one or
 * two error codes to differ.
 *
 * @see <a href="https://github.com/100thCoin/AccuracyCoin">AccuracyCoin</a>
 */
final class AccuracyCoinTests {
    private static final Logger logger = LoggerFactory.getLogger(AccuracyCoinTests.class);

    private static final String ROM = "/accuracycoin/AccuracyCoin.nes";

    /**
     * Zero page bytes the ROM keeps its own progress in.
     * <p>
     * {@code RUNNING_ALL_TESTS} is set for the whole of the all-test run and cleared when the
     * results screen is drawn, and {@code TEST_TALLY} counts the tests reached. Watching both is
     * what tells a finished run apart from one that has not started: the tally is also zero before
     * the first test.
     */
    private static final int RUNNING_ALL_TESTS = 0x0035;
    private static final int TEST_TALLY = 0x0037;
    private static final int PASS_TALLY = 0x0038;

    /**
     * How many scored tests the ROM has. The five on the Power On State page draw their findings
     * rather than judging them, score into page 3, and are not counted here or by the ROM.
     */
    private static final int TEST_COUNT = 141;

    /**
     * Frames to give the whole run. It takes a little under 4,000 -- the frame counter and DMC
     * tests each sit through many seconds of emulated time -- and the poll below stops as soon as
     * the ROM says it is done, so the slack costs nothing.
     */
    private static final long FRAME_BUDGET = 6_000;

    /**
     * When to start asking for the run, and how the ask is shaped.
     * <p>
     * The cursor boots on the page header, where Start means "run every test in the ROM". The menu
     * reads the controller once a frame and the ROM spends its first hundred-odd frames drawing
     * itself, so rather than guess the frame the menu goes live, this presses in pulses until the
     * ROM says the run has begun.
     */
    private static final long FIRST_PRESS_FRAME = 120;
    private static final long PRESS_PERIOD = 10;
    private static final long PRESS_LENGTH = 2;

    /**
     * Every result the ROM is not currently expected to write as a plain pass, in the order the
     * menu lists them.
     * <p>
     * Anything absent is expected to be {@code "PASS"}. Present entries are either a known failure
     * -- with the error code, so a test that starts failing a <em>different</em> way is caught too
     * -- or a pass carrying a success code, which is how the ROM marks a test with several
     * acceptable answers.
     */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            entry("$93   SHA indirect,Y", "PASS 1"),
            entry("$9F   SHA absolute,Y", "PASS 1"),
            entry("$9B   SHS absolute,Y", "PASS 1"),

            entry("DMA + $2002 Read", "PASS 1"),
            entry("DMA + $4016 Read", "PASS 1"),
            entry("Implicit DMA Abort", "PASS 2"),

            entry("APU Register Activation", "FAIL 7"),
            entry("Controller Clocking", "PASS 1"),

            entry("PPU Read Buffer", "PASS G"),

            entry("$2002 flag timing", "FAIL 1"),
            entry("Address $2004 behavior", "PASS G"),

            entry("Stale BG Shift Registers", "FAIL 3"),
            entry("Stale Sprite Shift Regs", "FAIL 3"),
            entry("BG Serial In", "FAIL 2"),
            entry("Sprites On Scanline 0", "FAIL 2"),
            entry("$2004 Stress Test", "FAIL 2"),
            entry("$2007 Stress Test", "FAIL 2"),
            entry("ALE + Read", "FAIL 2"),
            entry("Hybrid Addresses", "FAIL 2"),

            entry("Internal Data Bus", "FAIL 2")
    );

    /**
     * One scored test: which page of the menu it is on, what that page is called, what the test is
     * called, and the byte it reports into. Taken from the suite tables in {@code AccuracyCoin.asm},
     * so the names are the ones drawn on screen.
     */
    private record Check(int page, String suite, String name, int address) {
    }

    private static final List<Check> CHECKS = List.of(
            new Check(1, "CPU Behavior", "ROM is not writable", 0x0405),
            new Check(1, "CPU Behavior", "RAM Mirroring", 0x0403),
            new Check(1, "CPU Behavior", "PC Wraparound", 0x044D),
            new Check(1, "CPU Behavior", "The Decimal Flag", 0x0474),
            new Check(1, "CPU Behavior", "The B Flag", 0x0475),
            new Check(1, "CPU Behavior", "Dummy read cycles", 0x0406),
            new Check(1, "CPU Behavior", "Dummy write cycles", 0x0407),
            new Check(1, "CPU Behavior", "Open Bus", 0x0408),
            new Check(1, "CPU Behavior", "All NOP instructions", 0x047D),

            new Check(2, "Addressing mode wraparound", "Absolute Indexed", 0x046E),
            new Check(2, "Addressing mode wraparound", "Zero Page Indexed", 0x046F),
            new Check(2, "Addressing mode wraparound", "Indirect", 0x0470),
            new Check(2, "Addressing mode wraparound", "Indirect, X", 0x0471),
            new Check(2, "Addressing mode wraparound", "Indirect, Y", 0x0472),
            new Check(2, "Addressing mode wraparound", "Relative", 0x0473),

            new Check(3, "Unofficial Instructions: SLO", "$03   SLO indirect,X", 0x0409),
            new Check(3, "Unofficial Instructions: SLO", "$07   SLO zeropage", 0x040A),
            new Check(3, "Unofficial Instructions: SLO", "$0F   SLO absolute", 0x040B),
            new Check(3, "Unofficial Instructions: SLO", "$13   SLO indirect,Y", 0x040C),
            new Check(3, "Unofficial Instructions: SLO", "$17   SLO zeropage,X", 0x040D),
            new Check(3, "Unofficial Instructions: SLO", "$1B   SLO absolute,Y", 0x040E),
            new Check(3, "Unofficial Instructions: SLO", "$1F   SLO absolute,X", 0x040F),

            new Check(4, "Unofficial Instructions: RLA", "$23   RLA indirect,X", 0x0419),
            new Check(4, "Unofficial Instructions: RLA", "$27   RLA zeropage", 0x041A),
            new Check(4, "Unofficial Instructions: RLA", "$2F   RLA absolute", 0x041B),
            new Check(4, "Unofficial Instructions: RLA", "$33   RLA indirect,Y", 0x041C),
            new Check(4, "Unofficial Instructions: RLA", "$37   RLA zeropage,X", 0x041D),
            new Check(4, "Unofficial Instructions: RLA", "$3B   RLA absolute,Y", 0x041E),
            new Check(4, "Unofficial Instructions: RLA", "$3F   RLA absolute,X", 0x041F),

            new Check(5, "Unofficial Instructions: SRE", "$43   SRE indirect,X", 0x0420),
            new Check(5, "Unofficial Instructions: SRE", "$47   SRE zeropage", 0x047F),
            new Check(5, "Unofficial Instructions: SRE", "$4F   SRE absolute", 0x0422),
            new Check(5, "Unofficial Instructions: SRE", "$53   SRE indirect,Y", 0x0423),
            new Check(5, "Unofficial Instructions: SRE", "$57   SRE zeropage,X", 0x0424),
            new Check(5, "Unofficial Instructions: SRE", "$5B   SRE absolute,Y", 0x0425),
            new Check(5, "Unofficial Instructions: SRE", "$5F   SRE absolute,X", 0x0426),

            new Check(6, "Unofficial Instructions: RRA", "$63   RRA indirect,X", 0x0427),
            new Check(6, "Unofficial Instructions: RRA", "$67   RRA zeropage", 0x0428),
            new Check(6, "Unofficial Instructions: RRA", "$6F   RRA absolute", 0x0429),
            new Check(6, "Unofficial Instructions: RRA", "$73   RRA indirect,Y", 0x042A),
            new Check(6, "Unofficial Instructions: RRA", "$77   RRA zeropage,X", 0x042B),
            new Check(6, "Unofficial Instructions: RRA", "$7B   RRA absolute,Y", 0x042C),
            new Check(6, "Unofficial Instructions: RRA", "$7F   RRA absolute,X", 0x042D),

            new Check(7, "Unofficial Instructions: *AX", "$83   SAX indirect,X", 0x042E),
            new Check(7, "Unofficial Instructions: *AX", "$87   SAX zeropage", 0x042F),
            new Check(7, "Unofficial Instructions: *AX", "$8F   SAX absolute", 0x0430),
            new Check(7, "Unofficial Instructions: *AX", "$97   SAX zeropage,Y", 0x0431),
            new Check(7, "Unofficial Instructions: *AX", "$A3   LAX indirect,X", 0x0432),
            new Check(7, "Unofficial Instructions: *AX", "$A7   LAX zeropage", 0x0433),
            new Check(7, "Unofficial Instructions: *AX", "$AF   LAX absolute", 0x0434),
            new Check(7, "Unofficial Instructions: *AX", "$B3   LAX indirect,Y", 0x0435),
            new Check(7, "Unofficial Instructions: *AX", "$B7   LAX zeropage,Y", 0x0436),
            new Check(7, "Unofficial Instructions: *AX", "$BF   LAX absolute,Y", 0x0437),

            new Check(8, "Unofficial Instructions: DCP", "$C3   DCP indirect,X", 0x0438),
            new Check(8, "Unofficial Instructions: DCP", "$C7   DCP zeropage", 0x0439),
            new Check(8, "Unofficial Instructions: DCP", "$CF   DCP absolute", 0x043A),
            new Check(8, "Unofficial Instructions: DCP", "$D3   DCP indirect,Y", 0x043B),
            new Check(8, "Unofficial Instructions: DCP", "$D7   DCP zeropage,X", 0x043C),
            new Check(8, "Unofficial Instructions: DCP", "$DB   DCP absolute,Y", 0x043D),
            new Check(8, "Unofficial Instructions: DCP", "$DF   DCP absolute,X", 0x043E),

            new Check(9, "Unofficial Instructions: ISC", "$E3   ISC indirect,X", 0x043F),
            new Check(9, "Unofficial Instructions: ISC", "$E7   ISC zeropage", 0x0440),
            new Check(9, "Unofficial Instructions: ISC", "$EF   ISC absolute", 0x0441),
            new Check(9, "Unofficial Instructions: ISC", "$F3   ISC indirect,Y", 0x0442),
            new Check(9, "Unofficial Instructions: ISC", "$F7   ISC zeropage,X", 0x0443),
            new Check(9, "Unofficial Instructions: ISC", "$FB   ISC absolute,Y", 0x0444),
            new Check(9, "Unofficial Instructions: ISC", "$FF   ISC absolute,X", 0x0445),

            new Check(10, "Unofficial Instructions: SH*", "$93   SHA indirect,Y", 0x0446),
            new Check(10, "Unofficial Instructions: SH*", "$9F   SHA absolute,Y", 0x0447),
            new Check(10, "Unofficial Instructions: SH*", "$9B   SHS absolute,Y", 0x0448),
            new Check(10, "Unofficial Instructions: SH*", "$9C   SHY absolute,X", 0x0449),
            new Check(10, "Unofficial Instructions: SH*", "$9E   SHX absolute,Y", 0x044A),
            new Check(10, "Unofficial Instructions: SH*", "$BB   LAE absolute,Y", 0x044B),

            new Check(11, "Unofficial Immediates", "$0B   ANC Immediate", 0x0410),
            new Check(11, "Unofficial Immediates", "$2B   ANC Immediate", 0x0411),
            new Check(11, "Unofficial Immediates", "$4B   ASR Immediate", 0x0412),
            new Check(11, "Unofficial Immediates", "$6B   ARR Immediate", 0x0413),
            new Check(11, "Unofficial Immediates", "$8B   ANE Immediate", 0x0414),
            new Check(11, "Unofficial Immediates", "$AB   LXA Immediate", 0x0415),
            new Check(11, "Unofficial Immediates", "$CB   AXS Immediate", 0x0416),
            new Check(11, "Unofficial Immediates", "$EB   SBC Immediate", 0x0417),

            new Check(12, "CPU Interrupts", "Interrupt flag latency", 0x0461),
            new Check(12, "CPU Interrupts", "NMI Overlap BRK", 0x0462),
            new Check(12, "CPU Interrupts", "NMI Overlap IRQ", 0x0463),

            new Check(13, "APU Registers and DMA tests", "DMA + Open Bus", 0x046C),
            new Check(13, "APU Registers and DMA tests", "DMA + $2002 Read", 0x0488),
            new Check(13, "APU Registers and DMA tests", "DMA + $2007 Read", 0x044C),
            new Check(13, "APU Registers and DMA tests", "DMA + $2007 Write", 0x044F),
            new Check(13, "APU Registers and DMA tests", "DMA + $4015 Read", 0x045D),
            new Check(13, "APU Registers and DMA tests", "DMA + $4016 Read", 0x045E),
            new Check(13, "APU Registers and DMA tests", "DMC DMA Bus Conflicts", 0x046B),
            new Check(13, "APU Registers and DMA tests", "DMC DMA + OAM DMA", 0x0477),
            new Check(13, "APU Registers and DMA tests", "Explicit DMA Abort", 0x0479),
            new Check(13, "APU Registers and DMA tests", "Implicit DMA Abort", 0x0478),

            new Check(14, "APU Tests", "Length Counter", 0x0465),
            new Check(14, "APU Tests", "Length Table", 0x0466),
            new Check(14, "APU Tests", "Frame Counter IRQ", 0x0467),
            new Check(14, "APU Tests", "Frame Counter 4-step", 0x0468),
            new Check(14, "APU Tests", "Frame Counter 5-step", 0x0469),
            new Check(14, "APU Tests", "Delta Modulation Channel", 0x046A),
            new Check(14, "APU Tests", "APU Register Activation", 0x045C),
            new Check(14, "APU Tests", "Controller Strobing", 0x045F),
            new Check(14, "APU Tests", "Controller Clocking", 0x047A),

            new Check(16, "PPU Behavior", "CHR ROM is not writable", 0x0485),
            new Check(16, "PPU Behavior", "PPU Register Mirroring", 0x0404),
            new Check(16, "PPU Behavior", "PPU Register Open Bus", 0x044E),
            new Check(16, "PPU Behavior", "PPU Read Buffer", 0x0476),
            new Check(16, "PPU Behavior", "Palette RAM Quirks", 0x047E),
            new Check(16, "PPU Behavior", "Rendering Flag Behavior", 0x0486),
            new Check(16, "PPU Behavior", "$2007 read w/ rendering", 0x048A),
            new Check(16, "PPU Behavior", "Attributes As Tiles", 0x0481),

            new Check(17, "PPU VBlank Timing", "VBlank beginning", 0x0450),
            new Check(17, "PPU VBlank Timing", "VBlank end", 0x0451),
            new Check(17, "PPU VBlank Timing", "NMI Control", 0x0452),
            new Check(17, "PPU VBlank Timing", "NMI Timing", 0x0453),
            new Check(17, "PPU VBlank Timing", "NMI Suppression", 0x0454),
            new Check(17, "PPU VBlank Timing", "NMI at VBlank end", 0x0455),
            new Check(17, "PPU VBlank Timing", "NMI disabled at VBlank", 0x0456),

            new Check(18, "Sprite Evaluation", "Sprite overflow behavior", 0x0459),
            new Check(18, "Sprite Evaluation", "Sprite 0 Hit behavior", 0x0457),
            new Check(18, "Sprite Evaluation", "$2002 flag timing", 0x048D),
            new Check(18, "Sprite Evaluation", "Suddenly Resize Sprite", 0x0489),
            new Check(18, "Sprite Evaluation", "Arbitrary Sprite zero", 0x0458),
            new Check(18, "Sprite Evaluation", "Misaligned OAM behavior", 0x045A),
            new Check(18, "Sprite Evaluation", "Address $2004 behavior", 0x045B),
            new Check(18, "Sprite Evaluation", "OAM Corruption", 0x047B),
            new Check(18, "Sprite Evaluation", "INC $4014", 0x0480),

            new Check(19, "PPU Misc.", "t Register Quirks", 0x0482),
            new Check(19, "PPU Misc.", "Stale BG Shift Registers", 0x0483),
            new Check(19, "PPU Misc.", "Stale Sprite Shift Regs", 0x048F),
            new Check(19, "PPU Misc.", "BG Serial In", 0x0487),
            new Check(19, "PPU Misc.", "Sprites On Scanline 0", 0x0484),
            new Check(19, "PPU Misc.", "$2004 Stress Test", 0x048C),
            new Check(19, "PPU Misc.", "$2007 Stress Test", 0x048E),
            new Check(19, "PPU Misc.", "ALE + Read", 0x0491),
            new Check(19, "PPU Misc.", "Hybrid Addresses", 0x0492),

            new Check(20, "CPU Behavior 2", "Instruction Timing", 0x0460),
            new Check(20, "CPU Behavior 2", "Implied Dummy Reads", 0x046D),
            new Check(20, "CPU Behavior 2", "Branch Dummy Reads", 0x048B),
            new Check(20, "CPU Behavior 2", "JSR Edge Cases", 0x047C),
            new Check(20, "CPU Behavior 2", "Internal Data Bus", 0x0490)

    );

    @Test
    void everyTestInTheRomAgreesWithTheTable() throws IOException {
        var results = runEveryTest();
        var regressions = new ArrayList<String>();
        var stale = new ArrayList<String>();

        for (var check : CHECKS) {
            var expected = EXPECTED.getOrDefault(check.name(), "PASS");
            var actual = results.get(check.name());

            if (expected.equals(actual)) {
                continue;
            }

            var line = String.format(
                    "  page %-2d  %-28s  %-30s  expected %-7s got %s",
                    check.page(), check.suite(), check.name(), expected, actual
            );

            if (actual.startsWith("PASS")) {
                stale.add(line);
            } else {
                regressions.add(line);
            }
        }

        if (regressions.isEmpty() && stale.isEmpty()) {
            return;
        }

        var message = new StringBuilder("AccuracyCoin: ")
                .append(results.values().stream().filter(r -> r.startsWith("PASS")).count())
                .append(" of ").append(TEST_COUNT).append(" passed.\n");

        if (!regressions.isEmpty()) {
            message.append("\nRegressions -- these used to be better than this:\n")
                    .append(String.join("\n", regressions)).append('\n');
        }

        if (!stale.isEmpty()) {
            message.append("\nThe table is stale -- these now do better than it says, so take "
                            + "them out of EXPECTED:\n")
                    .append(String.join("\n", stale)).append('\n');
        }

        fail(message.toString());
    }

    /**
     * Boots the ROM, asks the menu for the whole suite, and reads the results page out of RAM.
     *
     * @return every test's name against what it reported, rendered the way {@link #EXPECTED} is.
     */
    private static Map<String, String> runEveryTest() throws IOException {
        var nes = load();
        var memory = nes.getMemory();
        var ppu = nes.getPPU();

        var results = new LinkedHashMap<String, String>();

        // Wall clock rather than frames: the frame budget is the emulated limit, and this one is
        // only here so a machine that has stopped making progress does not hang the build.
        assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            var started = false;

            while (ppu.getFrame() < FRAME_BUDGET) {
                var frame = ppu.getFrame();

                if (!started) {
                    var since = frame - FIRST_PRESS_FRAME;
                    var pressing = since >= 0 && since % PRESS_PERIOD < PRESS_LENGTH;
                    nes.getController1().setButtons(pressing ? Controller.BUTTON_START : 0);
                }

                advanceFrame(nes);

                if (memory.peek(RUNNING_ALL_TESTS) != 0) {
                    if (!started) {
                        nes.getController1().setButtons(0);
                        started = true;
                    }
                } else if (started && memory.peek(TEST_TALLY) == TEST_COUNT) {
                    var passed = memory.peek(PASS_TALLY);
                    logger.info(() -> "AccuracyCoin finished on frame " + ppu.getFrame()
                            + " with " + passed + " of " + TEST_COUNT + " passing");
                    return;
                }
            }

            fail(String.format(
                    "AccuracyCoin did not finish within %d frames (reached test %d of %d)",
                    FRAME_BUDGET, memory.peek(TEST_TALLY), TEST_COUNT
            ));
        });

        for (var check : CHECKS) {
            results.put(check.name(), describe(memory.peek(check.address())));
        }

        return results;
    }

    /**
     * What one result byte means, written the way the ROM draws it.
     * <p>
     * The code is rendered in base 36 because that is what the ROM prints -- it has one tile per
     * code and only 36 of them -- so an error here can be looked up in the README without being
     * converted first.
     */
    private static String describe(final int value) {
        if (value == 0xFF) {
            return "SKIPPED";
        }

        if (value == 0x00) {
            return "NOT RUN";
        }

        var code = value >> 2;
        var passed = (value & 1) != 0;

        if (passed && code == 0) {
            return "PASS";
        }

        var digit = code < 36
                ? String.valueOf(Character.toUpperCase(Character.forDigit(code, 36)))
                : String.valueOf(code);

        return (passed ? "PASS " : "FAIL ") + digit;
    }

    /**
     * Runs the machine until the PPU finishes the frame it is in the middle of.
     */
    private static void advanceFrame(final NES nes) {
        var ppu = nes.getPPU();
        var frame = ppu.getFrame();

        while (ppu.getFrame() == frame) {
            nes.tick();
        }
    }

    private static NES load() throws IOException {
        try (var romStream = AccuracyCoinTests.class.getResourceAsStream(ROM)) {
            assertNotNull(romStream, "ROM file not found: " + ROM);
            return new NES(Cart.load(romStream.readAllBytes(), ROM));
        }
    }
}
