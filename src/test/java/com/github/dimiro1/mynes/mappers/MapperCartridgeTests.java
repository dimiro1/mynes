package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The mappers with a whole console on top of them, driven by 6502 code out of the cartridge.
 * <p>
 * The unit tests next door call {@code prgRead} and {@code ppuAddress} directly, which proves the
 * chip and nothing around it. What they cannot reach is the part that only exists once everything
 * is wired together: that the header dispatches to the right class, that a write the CPU makes to
 * $8000 arrives as a bank switch, and -- the one that matters -- that the instruction fetched
 * <em>after</em> a bank switch comes out of the bank the game just asked for. A mapper can pass
 * every unit test and still leave the CPU executing whatever happens to be at the program
 * counter in a bank nobody chose.
 * <p>
 * So each cartridge here is a real one: a real iNES header, real 6502 in it, loaded through
 * {@link Cart#load} and run on a real {@link NES}. The program walks the banks, reads a byte out
 * of each that says which bank it is, and leaves the answers in zero page for the test to read
 * back. Every bank holds the same code at the same offset, which is what a game on a board with
 * no fixed window has to do and what makes the walk survive its own switching.
 */
class MapperCartridgeTests {
    /**
     * Where the program leaves what it read, and the byte it writes to {@code $10} once it has
     * finished. Without that marker a test cannot tell "the answers are wrong" from "the machine
     * never got that far".
     */
    private static final int RESULT = 0x00;
    private static final int DONE_FLAG = 0x10;
    private static final int DONE = 0x5A;

    /**
     * Where in each PRG bank the byte naming that bank goes. Far enough past the code not to
     * collide with it, and inside the switchable window on every board here.
     */
    private static final int SIGNATURE_OFFSET = 0x0100;
    private static final int SIGNATURE_ADDRESS = 0x8100;

    /**
     * Long enough for two frames of waiting for vblank and everything after it, and short enough
     * that a machine which has wedged fails rather than hangs.
     */
    private static final int CYCLE_BUDGET = 500_000;

    /**
     * One board: how its ROM is cut up, which address its bank register answers at, and what to
     * write there to reach bank <i>n</i>.
     */
    private record Board(
            String name,
            int mapper,
            int prgBanks,
            int prgBankSize,
            int bankRegister,
            int codeAddress,
            java.util.function.IntUnaryOperator select) {
    }

    @Nested
    @DisplayName("PRG banking, from the cartridge's own code")
    class PrgBanking {
        @ParameterizedTest(name = "${0}")
        @MethodSource("boards")
        void theCodeCanWalkItsOwnBanks(final String name, final Board board) {
            var memory = run(cartridge(board, bankWalk(board)));

            for (var bank = 0; bank < 4; bank++) {
                assertEquals(
                        bank,
                        memory[RESULT + bank],
                        name + " read the wrong bank back after switching to " + bank);
            }
        }

        @ParameterizedTest(name = "${0}")
        @MethodSource("boards")
        void andKeepsExecutingAcrossTheSwitch(final String name, final Board board) {
            var memory = run(cartridge(board, bankWalk(board)));

            assertEquals(
                    DONE,
                    memory[DONE_FLAG],
                    name + " never reached the end of its own program");
        }

        static Stream<Arguments> boards() {
            return Stream.of(
                    arguments("AxROM", board("AxROM", 7, 4, 0x8000, 0x8000, 0x8000, bank -> bank)),
                    arguments("MMC2", board("MMC2", 9, 8, 0x2000, 0xA000, 0xC000, bank -> bank)),
                    arguments("MMC4", board("MMC4", 10, 4, 0x4000, 0xA000, 0xC000, bank -> bank)),
                    arguments("Color Dreams",
                            board("Color Dreams", 11, 4, 0x8000, 0x8000, 0x8000, bank -> bank)),
                    arguments("GxROM",
                            board("GxROM", 66, 4, 0x8000, 0x8000, 0x8000, bank -> bank << 4)),
                    arguments("Camerica",
                            board("Camerica", 71, 4, 0x4000, 0xC000, 0xC000, bank -> bank)));
        }
    }

    @Nested
    @DisplayName("the MMC2 and MMC4 latch, through the PPU")
    class Latch {
        /**
         * The whole path: the CPU points $2006 at a tile the chip is watching for, the read of
         * $2007 puts that address on the PPU bus, and the <em>next</em> read of the pattern table
         * comes back out of the other bank. Nothing in the game writes a bank register between
         * the two.
         */
        @ParameterizedTest(name = "${0}")
        @MethodSource("latchBoards")
        void aTileFetchSwitchesTheWindowUnderneathTheGame(final String name, final Board board) {
            var memory = run(cartridge(board, latchWalk(board)));

            assertEquals(DONE, memory[DONE_FLAG], name + " never finished");
            assertEquals(1, memory[RESULT], name + " started in the wrong bank");
            assertEquals(
                    2,
                    memory[RESULT + 1],
                    name + " did not switch when the PPU fetched the tile that says to");
        }

        static Stream<Arguments> latchBoards() {
            return Stream.of(
                    arguments("MMC2", board("MMC2", 9, 8, 0x2000, 0xA000, 0xC000, bank -> bank)),
                    arguments("MMC4", board("MMC4", 10, 4, 0x4000, 0xA000, 0xC000, bank -> bank)));
        }
    }

    @Nested
    @DisplayName("CHR banking, through the PPU")
    class ChrBanking {
        @Test
        void gxromMovesThePatternTablesWithTheSameWriteThatMovesTheProgram() {
            var board = board("GxROM", 66, 4, 0x8000, 0x8000, 0x8000, bank -> bank << 4);
            var memory = run(cartridge(board, chrWalk(board, 0x2000)));

            assertEquals(DONE, memory[DONE_FLAG], "GxROM never finished");

            for (var bank = 0; bank < 4; bank++) {
                assertEquals(
                        bank,
                        memory[RESULT + bank],
                        "GxROM showed the wrong pattern table after selecting bank " + bank);
            }
        }
    }

    private static Board board(
            final String name,
            final int mapper,
            final int prgBanks,
            final int prgBankSize,
            final int bankRegister,
            final int codeAddress,
            final java.util.function.IntUnaryOperator select) {
        return new Board(name, mapper, prgBanks, prgBankSize, bankRegister, codeAddress, select);
    }

    /**
     * Switch to each of the first four banks in turn and write down the byte that says which one
     * answered.
     */
    private static List<Integer> bankWalk(final Board board) {
        var code = new ArrayList<Integer>();

        for (var bank = 0; bank < 4; bank++) {
            loadImmediate(code, board.select().applyAsInt(bank));
            storeAbsolute(code, board.bankRegister());
            loadAbsolute(code, SIGNATURE_ADDRESS);
            storeZeroPage(code, RESULT + bank);
        }

        return finish(code, board.codeAddress());
    }

    /**
     * Fill the two banks the lower window can show, read a byte of it, let the PPU fetch the tile
     * that trips the latch, and read the same byte again.
     */
    private static List<Integer> latchWalk(final Board board) {
        var code = new ArrayList<Integer>();

        waitForVBlank(code, board.codeAddress());
        waitForVBlank(code, board.codeAddress());

        // $B000 is the bank shown while the latch says $FD, $C000 the one it says $FE.
        loadImmediate(code, 1);
        storeAbsolute(code, 0xB000);
        loadImmediate(code, 2);
        storeAbsolute(code, 0xC000);

        readPatternByte(code, 0x0000);
        storeZeroPage(code, RESULT);

        // One read of $0FE8 and nothing else. The byte it returns is not wanted -- putting the
        // address on the bus is the whole point of it.
        pointPPUAt(code, 0x0FE8);
        loadAbsolute(code, 0x2007);

        readPatternByte(code, 0x0000);
        storeZeroPage(code, RESULT + 1);

        return finish(code, board.codeAddress());
    }

    /**
     * Select each CHR bank in turn and read a byte of the pattern table back through $2007.
     */
    private static List<Integer> chrWalk(final Board board, final int chrBankSize) {
        var code = new ArrayList<Integer>();

        waitForVBlank(code, board.codeAddress());
        waitForVBlank(code, board.codeAddress());

        for (var bank = 0; bank < 4; bank++) {
            // On GxROM the CHR bank is the low two bits of the same byte the PRG bank is in, so
            // this leaves the program in bank 0 throughout.
            loadImmediate(code, bank);
            storeAbsolute(code, board.bankRegister());
            readPatternByte(code, 0x0000);
            storeZeroPage(code, RESULT + bank);
        }

        return finish(code, board.codeAddress());
    }

    /**
     * {@code BIT $2002 / BPL -5}: spin until the PPU raises vblank. Two of these are how a game
     * waits out the warm up before it is allowed to write $2006.
     */
    private static void waitForVBlank(final List<Integer> code, final int codeAddress) {
        code.add(0x2C);
        code.add(0x02);
        code.add(0x20);
        code.add(0x10);
        code.add(0xFB);
    }

    /**
     * Points $2006 at an address and reads $2007 twice: the first read only primes the buffer,
     * and the second is the byte that was there. Leaves it in A.
     */
    private static void readPatternByte(final List<Integer> code, final int address) {
        pointPPUAt(code, address);
        loadAbsolute(code, 0x2007);
        loadAbsolute(code, 0x2007);
    }

    private static void pointPPUAt(final List<Integer> code, final int address) {
        loadImmediate(code, address >> 8);
        storeAbsolute(code, 0x2006);
        loadImmediate(code, address & 0xFF);
        storeAbsolute(code, 0x2006);
    }

    private static void loadImmediate(final List<Integer> code, final int value) {
        code.add(0xA9);
        code.add(value & 0xFF);
    }

    private static void loadAbsolute(final List<Integer> code, final int address) {
        code.add(0xAD);
        code.add(address & 0xFF);
        code.add(address >> 8);
    }

    private static void storeAbsolute(final List<Integer> code, final int address) {
        code.add(0x8D);
        code.add(address & 0xFF);
        code.add(address >> 8);
    }

    private static void storeZeroPage(final List<Integer> code, final int address) {
        code.add(0x85);
        code.add(address);
    }

    /**
     * Raises the flag that says the program ran to the end, then spins on itself.
     */
    private static List<Integer> finish(final List<Integer> code, final int codeAddress) {
        loadImmediate(code, DONE);
        storeZeroPage(code, DONE_FLAG);

        var here = codeAddress + code.size();
        code.add(0x4C);
        code.add(here & 0xFF);
        code.add(here >> 8);

        return code;
    }

    /**
     * Builds the iNES image: the header the board needs, then the same program and the same reset
     * vector stamped into every bank, and a byte in each saying which bank it is.
     */
    private static byte[] cartridge(final Board board, final List<Integer> code) {
        var chrBankSize = board.mapper() == 9 || board.mapper() == 10 ? 0x1000 : 0x2000;
        var chrSize = 4 * chrBankSize;
        var prgSize = board.prgBanks() * board.prgBankSize();
        var image = new byte[16 + prgSize + chrSize];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = (byte) (prgSize / 0x4000);
        image[5] = (byte) (chrSize / 0x2000);
        image[6] = (byte) ((board.mapper() & 0x0F) << 4);
        image[7] = (byte) (board.mapper() & 0xF0);

        for (var bank = 0; bank < board.prgBanks(); bank++) {
            var base = 16 + bank * board.prgBankSize();

            for (var i = 0; i < code.size(); i++) {
                image[base + i] = (byte) (int) code.get(i);
            }

            image[base + SIGNATURE_OFFSET] = (byte) bank;

            // The reset vector goes in every bank as well. Which bank is showing at $FFFC depends
            // on the board, and on the ones with no fixed window it depends on nothing at all.
            image[base + board.prgBankSize() - 4] = (byte) (board.codeAddress() & 0xFF);
            image[base + board.prgBankSize() - 3] = (byte) (board.codeAddress() >> 8);
        }

        var chrBase = 16 + prgSize;

        for (var i = 0; i < chrSize; i++) {
            image[chrBase + i] = (byte) (i / chrBankSize);
        }

        return image;
    }

    /**
     * Runs the cartridge until it says it has finished, or until the budget runs out.
     *
     * @return the first page of RAM, which is where the program left its answers.
     */
    private static int[] run(final byte[] image) {
        var nes = new NES(Cart.load(image, "cartridge.nes"));
        nes.step();

        var memory = nes.getMemory();

        while (nes.getCPU().getState().cycles() < CYCLE_BUDGET
                && memory.peek(DONE_FLAG) != DONE) {
            nes.step();
        }

        var page = new int[0x100];

        for (var i = 0; i < page.length; i++) {
            page[i] = memory.peek(i);
        }

        return page;
    }
}
