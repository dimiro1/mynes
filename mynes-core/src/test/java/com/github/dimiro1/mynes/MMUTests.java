package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.cheat.GameGenie;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mirroring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for address decoding and OAM DMA.
 * <p>
 * Neither is reachable from the ROM based tests: the blargg and nestest ROMs never write $4014,
 * so the DMA engine has never actually run.
 */
public class MMUTests {
    /**
     * One cycle of whatever the transfer engine wants, standing in for the CPU.
     * <p>
     * A halt cycle is answered as though the CPU had spent it reading, which is what it does
     * except in the handful of cycles an instruction spends writing.
     *
     * @return true if the CPU would have been held off the bus.
     */
    private static boolean stallCycle(final MMU mmu, final long cpuCycle) {
        var kind = mmu.beginDMACycle(cpuCycle);

        if (kind == CPUBus.DMACycle.HALT) {
            mmu.endHaltCycle(false);
        }

        return kind != CPUBus.DMACycle.NONE;
    }

    private static final int OAM_DMA = 0x4014;
    private static final int OAM_DATA_REGISTER = 0x04;

    // One halt cycle plus 256 read/write pairs, and one more when the halt lands on an odd cycle.
    private static final int ALIGNED_DMA_CYCLES = 513;
    private static final int UNALIGNED_DMA_CYCLES = 514;

    private PPU ppu;
    private MMU mmu;

    @BeforeEach
    void setUp() {
        var mapper = new Mapper0(new byte[0x4000], new byte[0x2000], Mirroring.HORIZONTAL);
        ppu = new PPU(level -> { }, mapper);
        mmu = new MMU(ppu, new APU(level -> { }, level -> { }), mapper, null, null);
    }

    @Nested
    class AddressDecoding {
        @ParameterizedTest
        @ValueSource(ints = {0x0000, 0x0800, 0x1000, 0x1800})
        void internalRamIsMirroredEveryTwoKilobytes(final int base) {
            mmu.write(base + 0x0123, 0x5A);

            assertEquals(0x5A, mmu.read(0x0123), "should alias down to the real RAM");
            assertEquals(0x5A, mmu.read(0x0923));
            assertEquals(0x5A, mmu.read(0x1123));
            assertEquals(0x5A, mmu.read(0x1923));
        }

        @Test
        void theLastMirroredByteIsTheLastByteOfRam() {
            mmu.write(0x1FFF, 0x42);

            assertEquals(0x42, mmu.read(0x07FF));
        }

        @Test
        void ppuRegistersAreMirroredEveryEightBytes() {
            // OAM is the only PPU register pair that reads back what was written without any
            // buffering or side effects, so the round trip has to go through $2003 and $2004.
            // $3FF3 and $3FFC are the last mirrors of those two.
            mmu.write(0x3FF3, 0x10);
            mmu.write(0x3FFC, 0x42);

            mmu.write(0x2003, 0x10);
            assertEquals(0x42, mmu.read(0x2004), "the write through the last mirror should land");
        }

        @Test
        void peekDoesNotTouchRegisters() {
            mmu.write(0x0100, 0x99);

            assertEquals(0x99, mmu.peek(0x0100), "plain memory reads back normally");
            assertEquals(0, mmu.peek(0x2002), "PPU registers must not be read for real");
            assertEquals(0, mmu.peek(0x4016), "controller ports must not be clocked");
        }
    }

    /**
     * The seam a debugger's watchpoints hang off. What matters is not only that it sees CPU writes
     * but exactly which writes it does not see, since that is the difference between a watchpoint
     * that misses something and one whose limits are known.
     */
    @Nested
    class WriteListener {
        private final List<String> seen = new ArrayList<>();

        private void record() {
            mmu.setWriteListener((address, value) ->
                    seen.add(String.format("%04X=%02X", address, value)));
        }

        @Test
        void seesEveryByteTheCpuWrites() {
            record();

            mmu.write(0x0123, 0x5A);
            mmu.write(0x8000, 0x99);

            assertEquals(List.of("0123=5A", "8000=99"), seen);
        }

        @Test
        void seesTheMirrorItWasWrittenThroughRatherThanTheRamBehindIt() {
            record();

            mmu.write(0x1FFF, 0x42);

            assertEquals(List.of("1FFF=42"), seen, "where the CPU put it is what a watch is set on");
        }

        @Test
        void isToldTheValueBeforeItLands() {
            mmu.write(0x0100, 0x11);

            var wasThere = new int[1];
            mmu.setWriteListener((address, value) -> wasThere[0] = mmu.peek(address));

            mmu.write(0x0100, 0x22);

            assertEquals(0x11, wasThere[0], "the byte is handed over because it cannot be read yet");
            assertEquals(0x22, mmu.peek(0x0100), "and it lands afterwards");
        }

        @Test
        void readingDoesNotFireIt() {
            mmu.write(0x0100, 0x99);
            record();

            mmu.read(0x0100);
            mmu.peek(0x0100);
            mmu.read(0x2002);

            assertEquals(List.of(), seen);
        }

        /**
         * The documented hole, pinned so that closing it is a decision rather than an accident: a
         * sprite DMA copies into OAM by calling the PPU directly, so a watch on $2004 sleeps
         * through all 256 of the writes it makes.
         */
        @Test
        void aSpriteDmaIsNotSeen() {
            for (var i = 0; i < 0x100; i++) {
                mmu.write(0x0200 + i, i);
            }

            record();
            mmu.write(OAM_DMA, 0x02);

            for (var cycle = 0; cycle < 600 && stallCycle(mmu, cycle); cycle++) {
                // Every one of the 256 copies, so that a listener that saw any of them would have.
            }

            assertEquals(List.of("4014=02"), seen, "the register write, and none of the transfer");
        }

        @Test
        void clearingItStopsTheTelling() {
            record();
            mmu.write(0x0100, 0x11);

            mmu.setWriteListener(null);
            mmu.write(0x0100, 0x22);

            assertEquals(List.of("0100=11"), seen);
        }
    }

    /**
     * The seam a Game Genie hangs off, which is the read side of the same idea.
     * <p>
     * The codes below are real published ones rather than records built by hand, so that the scramble
     * in {@code GameGenieCodeTests} and the wiring here are checked against each other: a shift moved
     * in one of them and these stop firing at the address the other expects.
     * <p>
     * The cartridge is one 16KB bank, which makes $8000 and $C000 the same bytes, and its PRG is
     * stamped so that every byte says where it came from.
     */
    @Nested
    class GameGenieCodes {
        /** $91D9 = $AD, no compare: Super Mario Bros. infinite lives. */
        private static final String LIVES = "SXIOPO";

        /** $94A7 = $02, but only where the cartridge answers $03. */
        private static final String BANKED = "ZEXPYGLA";

        private static final int LIVES_AT = 0x91D9;
        private static final int BANKED_AT = 0x94A7;

        private NES nes;
        private MMU bus;
        private GameGenie genie;

        @BeforeEach
        void plugIn() {
            nes = new NES(Cart.load(stamped(), "genie.nes"));
            bus = nes.getMemory();
            genie = new GameGenie();
            genie.attach(nes);
        }

        @Test
        void theCartridgeAnswersForItselfUntilThereIsACodeIn() {
            assertEquals(0xD9, bus.read(LIVES_AT), "the stamp, which is the low byte of the offset");
        }

        @Test
        void aCodeAnswersInsteadOfTheCartridge() {
            genie.add(GameGenieCode.decode(LIVES));

            assertEquals(0xAD, bus.read(LIVES_AT));
        }

        @Test
        void everyOtherAddressIsLeftAlone() {
            genie.add(GameGenieCode.decode(LIVES));

            assertEquals(0xD8, bus.read(LIVES_AT - 1));
            assertEquals(0xDA, bus.read(LIVES_AT + 1));
        }

        /**
         * The device is a pass-through on /ROMSEL, so fifteen address bits is all it sees and there is
         * no code for anywhere below $8000. Cartridge RAM and the console's own memory are unreachable
         * by construction rather than by a check.
         */
        @Test
        void nothingBelowProgramRomIsReachableByACode() {
            genie.add(GameGenieCode.decode(LIVES));
            bus.write(0x0100, 0x11);

            assertEquals(0x11, bus.read(0x0100));
            assertEquals(0, bus.read(0x6000), "this cartridge has no RAM, and a code cannot add one");
        }

        /**
         * A code names a CPU address, not an offset into the ROM. This cartridge answers with the same
         * byte at $91D9 and at $D1D9 because one bank is mirrored into both halves, and a code on the
         * first must not fire on the second.
         */
        @Test
        void aCodeIsPinnedToTheAddressItNamesEvenWhereTheCartridgeMirrorsItself() {
            genie.add(GameGenieCode.decode(LIVES));

            assertEquals(0xAD, bus.read(LIVES_AT));
            assertEquals(0xD9, bus.read(LIVES_AT | 0x4000), "the same ROM byte, the other address");
        }

        @Test
        void aCompareCodeFiresOnTheByteItWasWrittenFor() {
            genie.add(GameGenieCode.decode(BANKED));

            assertEquals(0x03, stampedByteAt(BANKED_AT), "the cartridge is answering what it expects");
            assertEquals(0x02, bus.read(BANKED_AT));
        }

        @Test
        void aCompareCodeLeavesEveryOtherBankAlone() {
            genie.add(new GameGenieCode(BANKED, BANKED_AT, 0x02, 0x7B));

            assertEquals(0x03, bus.read(BANKED_AT), "the cartridge answered $03, and the code wants $7B");
        }

        /**
         * It is the Genie driving the data pins and not the cartridge, so what it put out is what the
         * pins keep -- and $4020 is a window nothing answers to, which reads back off them.
         */
        @Test
        void theSubstitutedByteIsWhatTheOpenBusKeeps() {
            genie.add(GameGenieCode.decode(LIVES));

            bus.read(LIVES_AT);

            assertEquals(0xAD, bus.read(0x4020), "not $D9, which is what the cartridge said");
        }

        /**
         * A disassembly of the substituted byte is a listing of the instructions that actually run,
         * which is the only reading of it worth showing anybody.
         */
        @Test
        void lookingWithoutReadingSeesTheSubstitutionToo() {
            genie.add(GameGenieCode.decode(LIVES));

            assertEquals(0xAD, bus.peek(LIVES_AT));
        }

        @Test
        void lookingIsStillNotABusCycle() {
            genie.add(GameGenieCode.decode(LIVES));

            bus.read(0x0100);
            var onThePins = bus.read(0x4020);

            bus.peek(LIVES_AT);

            assertEquals(onThePins, bus.read(0x4020), "peeking must not have refreshed them");
        }

        /**
         * The hook is in the bus decode rather than in the processor's read, so a transfer picks the
         * substitutions up as well. The device cannot tell which unit inside the 2A03 is driving the
         * address, and neither should this.
         */
        @Test
        void aSpriteDmaOutOfProgramRomCarriesTheSubstitutions() {
            genie.add(GameGenieCode.decode(LIVES));

            bus.write(OAM_DMA, LIVES_AT >> 8);

            for (var cycle = 0; cycle < 600 && stallCycle(bus, cycle); cycle++) {
                // Every one of the 256 copies.
            }

            assertEquals(0xAD, readOAM(LIVES_AT & 0xFF), "the byte the code answers with");
            assertEquals(0xD8, readOAM((LIVES_AT - 1) & 0xFF), "and the cartridge's either side of it");
        }

        @Test
        void takingTheLastCodeAwayPutsTheCartridgeBack() {
            var code = GameGenieCode.decode(LIVES);

            genie.add(code);
            assertEquals(0xAD, bus.read(LIVES_AT));

            assertTrue(genie.remove(code));
            assertEquals(0xD9, bus.read(LIVES_AT));
        }

        @Test
        void clearingDoesTheSameForTheLot() {
            genie.add(GameGenieCode.decode(LIVES));
            genie.add(GameGenieCode.decode(BANKED));

            genie.clear();

            assertTrue(genie.isEmpty());
            assertEquals(0xD9, bus.read(LIVES_AT));
            assertEquals(0x03, bus.read(BANKED_AT));
        }

        /**
         * The real device answers the bus in the time it has and does not get a second look, so it
         * cannot hold two codes for one address. Neither does this: the newer one wins outright rather
         * than stacking behind the older.
         */
        @Test
        void oneAddressHoldsOneCode() {
            var first = GameGenieCode.decode(LIVES);
            var second = new GameGenieCode("SECOND", LIVES_AT, 0x60, GameGenieCode.NO_COMPARE);

            genie.add(first);
            var replaced = genie.add(second);

            assertEquals(first, replaced);
            assertEquals(List.of(second), genie.codes());
            assertEquals(0x60, bus.read(LIVES_AT));
        }

        /**
         * Which order the two happen in is not the front end's problem: the window holds its codes
         * across a power cycle and hands them to whichever machine is built next.
         */
        @Test
        void codesPutInBeforeTheMachineArrivesStillFire() {
            var waiting = new GameGenie();
            waiting.add(GameGenieCode.decode(LIVES));

            var second = new NES(Cart.load(stamped(), "genie.nes"));
            waiting.attach(second);

            assertEquals(0xAD, second.getMemory().read(LIVES_AT));
        }

        private int readOAM(final int address) {
            bus.write(0x2003, address);
            return nes.getPPU().read(OAM_DATA_REGISTER);
        }

        private int stampedByteAt(final int address) {
            return Byte.toUnsignedInt(stamped()[16 + (address & 0x3FFF)]);
        }

        /**
         * One 16KB bank, every byte of it saying which offset it came from -- so a read that answers
         * with the wrong address is visible rather than merely wrong. The one exception is the byte
         * {@link #BANKED} compares against, which is made to be the $03 that code was published for.
         */
        private byte[] stamped() {
            var image = new byte[16 + 0x4000];

            image[0] = 'N';
            image[1] = 'E';
            image[2] = 'S';
            image[3] = 0x1A;
            image[4] = 1;  // one PRG bank, mirrored into both $8000 and $C000. No CHR, so CHR RAM.

            for (var i = 0; i < 0x4000; i++) {
                image[16 + i] = (byte) i;
            }

            image[16 + (BANKED_AT & 0x3FFF)] = 0x03;

            return image;
        }
    }

    @Nested
    class OamDma {
        @Test
        void transfersAPageIntoOamAndStallsFor513CyclesWhenAligned() {
            fillPage(0x02);

            mmu.write(OAM_DMA, 0x02);
            var stalled = runToCompletion(0);

            assertEquals(ALIGNED_DMA_CYCLES, stalled);
            assertFalse(mmu.isDMAInProgress(), "the transfer should have finished");
            assertEquals(
                    0xFF, readOAM(0xFF),
                    "the last byte of the page should be the last thing written to OAMDATA"
            );
        }

        @Test
        void stallsForOneMoreCycleWhenTheHaltLandsOnAnOddCycle() {
            fillPage(0x02);

            mmu.write(OAM_DMA, 0x02);
            var stalled = runToCompletion(1);

            assertEquals(UNALIGNED_DMA_CYCLES, stalled);
        }

        @Test
        void copiesFromTheRequestedPage() {
            // Two candidate pages, so a transfer from the wrong one is visible.
            for (var i = 0; i < 0x100; i++) {
                mmu.write(0x0200 + i, 0xAA);
                mmu.write(0x0300 + i, i);
            }

            mmu.write(OAM_DMA, 0x03);
            runToCompletion(0);

            assertEquals(0xFF, readOAM(0xFF));
        }

        @Test
        void doesNotStallWhenIdle() {
            assertFalse(stallCycle(mmu, 0), "no transfer requested");
            assertFalse(mmu.isDMAInProgress());
        }

        /**
         * Reads one byte of OAM.
         * <p>
         * A whole page transfer leaves OAMADDR back where it started, so the address has to be
         * pointed at the byte of interest before $2004 will show it.
         */
        private int readOAM(final int address) {
            mmu.write(0x2003, address);
            return ppu.read(OAM_DATA_REGISTER);
        }

        private void fillPage(final int page) {
            for (var i = 0; i < 0x100; i++) {
                mmu.write((page << 8) | i, i);
            }
        }

        /**
         * Ticks the DMA engine until it lets go of the bus.
         *
         * @param firstCycle the CPU cycle counter the first stalled cycle happens on.
         * @return how many cycles the CPU was stalled for.
         */
        private int runToCompletion(final long firstCycle) {
            var stalled = 0;

            while (stallCycle(mmu, firstCycle + stalled)) {
                stalled++;

                if (stalled > 1000) {
                    throw new AssertionError("DMA never finished");
                }
            }

            return stalled;
        }
    }

    @Nested
    class CpuIntegration {
        @Test
        void writingOamDmaStallsTheCpuThenLetsItCarryOn() {
            // LDA #$02 / STA $4014 / NOP, run from the reset vector.
            var nes = new NES(Cart.load(rom(0xA9, 0x02, 0x8D, 0x14, 0x40, 0xEA), "dma.nes"));
            var cpu = nes.getCPU();

            cpu.step();  // the reset sequence
            cpu.step();  // LDA #$02

            var beforeStore = cpu.getState().cycles();
            cpu.step();  // STA $4014, which starts the transfer

            var storeCycles = cpu.getState().cycles() - beforeStore;
            assertEquals(4, storeCycles, "the store itself is four cycles");
            assertTrue(nes.getBus().isDMAInProgress(), "the write should have armed the transfer");

            var beforeNop = cpu.getState().cycles();
            cpu.step();  // the NOP, which cannot start until the transfer is done

            var stalledPlusNop = cpu.getState().cycles() - beforeNop;
            var expectedStall = (beforeNop & 1) == 0 ? ALIGNED_DMA_CYCLES : UNALIGNED_DMA_CYCLES;

            assertFalse(nes.getBus().isDMAInProgress(), "the transfer should have finished");
            assertEquals(
                    expectedStall + 2, stalledPlusNop,
                    "expected the stall plus the two cycle NOP"
            );
            assertEquals(0xC006, cpu.getState().pc(), "and then execution carries on");
        }

        /**
         * Builds a mapper 0 image whose single PRG bank holds {@code code} at $C000, with the
         * reset vector pointing at it.
         */
        private byte[] rom(final int... code) {
            var image = new byte[16 + 0x4000];

            image[0] = 'N';
            image[1] = 'E';
            image[2] = 'S';
            image[3] = 0x1A;
            image[4] = 1;  // one PRG bank, mirrored into both $8000 and $C000

            for (var i = 0; i < code.length; i++) {
                image[16 + i] = (byte) code[i];
            }

            // Reset vector at $FFFC, which is the last-but-four byte of the bank.
            image[16 + 0x3FFC] = 0x00;
            image[16 + 0x3FFD] = (byte) 0xC0;

            return image;
        }
    }
}
