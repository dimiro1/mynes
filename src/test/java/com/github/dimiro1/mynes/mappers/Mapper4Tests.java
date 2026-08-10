package com.github.dimiro1.mynes.mappers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MMC3: fine grained banking, and a counter that turns PPU address line A12 into scanlines.
 */
class Mapper4Tests {
    /**
     * A stretch of A12 being low that is comfortably longer than the chip's filter, which is what
     * the sprite fetch phase of a real scanline looks like.
     */
    private static final int LONG_LOW = 20;

    @Nested
    @DisplayName("PRG banking")
    class PrgBanking {
        @Test
        void modeZeroSwitchesTheLowerHalfAndFixesTheUpper() {
            var mapper = mmc3();
            setBank(mapper, 6, 3);
            setBank(mapper, 7, 5);

            assertEquals(3, mapper.prgRead(0x8000));
            assertEquals(5, mapper.prgRead(0xA000));
            assertEquals(6, mapper.prgRead(0xC000), "the second to last page");
            assertEquals(7, mapper.prgRead(0xE000), "and the last one, where the vectors live");
        }

        @Test
        void modeOneSwapsTheFixedPageWithTheOneAtEightThousand() {
            var mapper = mmc3();
            setBank(mapper, 0x40 | 6, 3);
            setBank(mapper, 0x40 | 7, 5);

            assertEquals(6, mapper.prgRead(0x8000), "the second to last page moves down here");
            assertEquals(5, mapper.prgRead(0xA000), "R7 stays where it is either way");
            assertEquals(3, mapper.prgRead(0xC000));
            assertEquals(7, mapper.prgRead(0xE000), "and the last page never moves");
        }

        @Test
        void thePagesAreEightKilobytesWide() {
            var rom = new byte[2 * 0x2000];
            rom[0x0000] = 0x11;
            rom[0x1FFF] = 0x22;
            rom[0x2000] = 0x33;
            rom[0x3FFF] = 0x44;

            var mapper = new Mapper4(rom, new byte[0], Mirroring.VERTICAL);

            assertEquals(0x11, mapper.prgRead(0x8000));
            assertEquals(0x22, mapper.prgRead(0x9FFF));
            assertEquals(0x33, mapper.prgRead(0xE000));
            assertEquals(0x44, mapper.prgRead(0xFFFF));
        }

        @Test
        void bankNumbersAreFoldedThroughHowManyPagesThereAre() {
            var mapper = mmc3();
            setBank(mapper, 6, 8 + 3);

            assertEquals(3, mapper.prgRead(0x8000), "an eight page board decodes three bits");
        }
    }

    @Nested
    @DisplayName("CHR banking")
    class ChrBanking {
        @Test
        void theFirstTwoRegistersCoverTwoKilobytesEach() {
            var mapper = mmc3();
            setBank(mapper, 0, 4);
            setBank(mapper, 1, 8);

            assertEquals(4, mapper.charRead(0x0000));
            assertEquals(5, mapper.charRead(0x0400), "the other half of the pair comes with it");
            assertEquals(8, mapper.charRead(0x0800));
            assertEquals(9, mapper.charRead(0x0C00));
        }

        @Test
        void theTwoKilobyteRegistersIgnoreTheLowBitOfTheBankNumber() {
            var mapper = mmc3();
            setBank(mapper, 0, 5);

            assertEquals(4, mapper.charRead(0x0000), "bank 5 names the pair that starts at 4");
            assertEquals(5, mapper.charRead(0x0400));
        }

        @Test
        void theOtherFourRegistersCoverOneKilobyteEach() {
            var mapper = mmc3();
            setBank(mapper, 2, 10);
            setBank(mapper, 3, 11);
            setBank(mapper, 4, 12);
            setBank(mapper, 5, 13);

            assertEquals(10, mapper.charRead(0x1000));
            assertEquals(11, mapper.charRead(0x1400));
            assertEquals(12, mapper.charRead(0x1800));
            assertEquals(13, mapper.charRead(0x1C00));
        }

        @Test
        void inversionSwapsTheTwoHalvesOfTheWindow() {
            var mapper = mmc3();
            setBank(mapper, 0x80, 4);           // R0, the first 2KB pair
            setBank(mapper, 0x80 | 2, 10);      // R2, the first 1KB page

            assertEquals(4, mapper.charRead(0x1000), "the 2KB pairs move up");
            assertEquals(5, mapper.charRead(0x1400));
            assertEquals(10, mapper.charRead(0x0000), "and the 1KB pages move down");
        }

        @Test
        void chrRamTakesWrites() {
            var mapper = new Mapper4(StampedROM.of(8, 0x2000), new byte[0], Mirroring.VERTICAL);

            // Lay the eight pages out in order, so the pattern tables read as flat RAM.
            setBank(mapper, 0, 0);
            setBank(mapper, 1, 2);
            setBank(mapper, 2, 4);
            setBank(mapper, 3, 5);
            setBank(mapper, 4, 6);
            setBank(mapper, 5, 7);

            mapper.charWrite(0x0000, 0x11);
            mapper.charWrite(0x1FFF, 0x22);

            assertEquals(0x11, mapper.charRead(0x0000));
            assertEquals(0x22, mapper.charRead(0x1FFF));
        }

        @Test
        void chrRomDoesNot() {
            var mapper = mmc3();

            mapper.charWrite(0x0000, 0x11);

            assertEquals(0, mapper.charRead(0x0000), "bank 0's stamp, not the write");
        }
    }

    @Nested
    @DisplayName("mirroring")
    class MirroringModes {
        @Test
        void theRegisterPicksBetweenVerticalAndHorizontal() {
            var mapper = mmc3();

            mapper.prgWrite(0xA000, 0);
            assertEquals(Mirroring.VERTICAL, mapper.mirroring());

            mapper.prgWrite(0xA000, 1);
            assertEquals(Mirroring.HORIZONTAL, mapper.mirroring());
        }

        @Test
        void aFourScreenBoardIgnoresTheRegister() {
            var mapper = new Mapper4(
                    StampedROM.of(8, 0x2000), StampedROM.of(64, 0x400), Mirroring.FOUR_SCREEN
            );

            mapper.prgWrite(0xA000, 1);

            assertEquals(
                    Mirroring.FOUR_SCREEN, mapper.mirroring(),
                    "there is nothing to switch: the nametable RAM is on the cartridge"
            );
        }
    }

    @Nested
    @DisplayName("cartridge RAM")
    class CartridgeRAM {
        @Test
        void isReadableAndWritableOnPowerUp() {
            var mapper = mmc3();

            mapper.prgRAMWrite(0x6000, 0x42);
            mapper.prgRAMWrite(0x7FFF, 0x43);

            assertEquals(0x42, mapper.prgRAMRead(0x6000));
            assertEquals(0x43, mapper.prgRAMRead(0x7FFF));
        }

        @Test
        void writeProtectionDropsWritesAndKeepsWhatIsThere() {
            var mapper = mmc3();
            mapper.prgRAMWrite(0x6123, 0x42);

            mapper.prgWrite(0xA001, 0xC0);  // enabled, write protected
            mapper.prgRAMWrite(0x6123, 0x99);

            assertEquals(0x42, mapper.prgRAMRead(0x6123));
        }

        @Test
        void disablingTheChipHidesItWithoutLosingWhatIsThere() {
            var mapper = mmc3();
            mapper.prgRAMWrite(0x6123, 0x42);

            mapper.prgWrite(0xA001, 0x00);

            assertEquals(0, mapper.prgRAMRead(0x6123), "nothing is driving the bus");

            mapper.prgWrite(0xA001, 0x80);

            assertEquals(0x42, mapper.prgRAMRead(0x6123));
        }
    }

    @Nested
    @DisplayName("the scanline counter")
    class ScanlineCounter {
        private RecordingIRQ irq;
        private Mapper4 mapper;

        @BeforeEach
        void setUp() {
            irq = new RecordingIRQ();
            mapper = mmc3();
            mapper.setIRQHandler(irq);
        }

        @Test
        void firesTheScanlineAfterTheLatchRunsOut() {
            arm(3);

            scanline();  // the reload, which puts 3 in the counter
            scanline();  // 2
            scanline();  // 1
            assertFalse(irq.asserted(), "not yet");

            scanline();  // 0
            assertTrue(irq.asserted());
        }

        @Test
        void reloadsItselfAndFiresAgainEveryLatchPlusOneScanlines() {
            arm(2);

            for (var i = 0; i < 3; i++) {
                scanline();
            }
            assertTrue(irq.asserted(), "reload, 1, 0");

            mapper.prgWrite(0xE000, 0);  // acknowledge
            mapper.prgWrite(0xE001, 0);  // and re-enable
            assertFalse(irq.asserted());

            scanline();
            scanline();
            assertFalse(irq.asserted());

            scanline();
            assertTrue(irq.asserted(), "the latch is a period, not a one shot");
        }

        @Test
        void writingE000ReleasesTheLine() {
            arm(1);
            scanline();
            scanline();
            assertTrue(irq.asserted());

            mapper.prgWrite(0xE000, 0);

            assertFalse(irq.asserted(), "which is how the handler stops the CPU coming straight back");
        }

        @Test
        void aDisabledCounterKeepsCountingButNeverFires() {
            mapper.prgWrite(0xC000, 1);
            mapper.prgWrite(0xC001, 0);

            scanline();
            scanline();

            assertFalse(irq.asserted());
        }

        @Test
        void enablingDoesNotFireForSomethingThatAlreadyHappened() {
            mapper.prgWrite(0xC000, 1);
            mapper.prgWrite(0xC001, 0);
            scanline();  // reload: 1
            scanline();  // 0, but the interrupt is disabled

            mapper.prgWrite(0xE001, 0);

            assertFalse(irq.asserted(), "an empty counter is not an interrupt");

            scanline();  // empty, so it reloads to 1 instead of counting
            assertFalse(irq.asserted());

            scanline();  // 0, and this time it is armed
            assertTrue(irq.asserted());
        }

        @Test
        void aLatchOfZeroFiresEveryScanline() {
            arm(0);

            scanline();
            assertTrue(irq.asserted());

            mapper.prgWrite(0xE000, 0);
            mapper.prgWrite(0xE001, 0);
            scanline();

            assertTrue(irq.asserted());
        }

        @Test
        void writingC001MakesTheNextScanlineReloadRatherThanCount() {
            arm(5);
            scanline();  // 5
            scanline();  // 4

            mapper.prgWrite(0xC001, 0);

            for (var i = 0; i < 5; i++) {
                scanline();  // the reload, then 4, 3, 2, 1
                assertFalse(irq.asserted(), "restarted from the latch, not from 4");
            }

            scanline();
            assertTrue(irq.asserted());
        }

        @Test
        void aNewLatchOnlyTakesEffectAtTheNextReload() {
            arm(3);
            scanline();  // 3

            mapper.prgWrite(0xC000, 100);

            scanline();  // 2
            scanline();  // 1
            scanline();  // 0

            assertTrue(irq.asserted(), "the count in progress was left alone");
        }

        /**
         * Points the counter at a scanline and turns the interrupt on, the way a game's setup
         * code does: latch, reload, enable.
         */
        private void arm(final int latch) {
            mapper.prgWrite(0xC000, latch);
            mapper.prgWrite(0xC001, 0);
            mapper.prgWrite(0xE001, 0);
        }

        private void scanline() {
            riseAfterLow(mapper, LONG_LOW);
        }
    }

    @Nested
    @DisplayName("the A12 filter")
    class A12Filter {
        @ParameterizedTest(name = "${0} dots low is a glitch")
        @ValueSource(ints = {0, 1, 3, 9})
        void aRiseAfterATooShortDipDoesNotCount(final int dots) {
            assertFalse(clocksAfterLow(dots), "the fetch pipeline flips A12 like this all line");
        }

        @ParameterizedTest(name = "${0} dots low is a scanline")
        @ValueSource(ints = {10, 12, 68, 256})
        void aRiseAfterALongEnoughDipCounts(final int dots) {
            assertTrue(clocksAfterLow(dots));
        }

        @Test
        void stayingHighDoesNotCountTwice() {
            var irq = new RecordingIRQ();
            var mapper = armedMapper(irq);

            riseAfterLow(mapper, LONG_LOW);
            assertTrue(irq.asserted());

            mapper.prgWrite(0xE000, 0);
            mapper.prgWrite(0xE001, 0);

            // More reads from the same pattern table, with no dip in between.
            mapper.ppuAddress(0x1234);
            mapper.ppuAddress(0x1FFF);

            assertFalse(irq.asserted(), "only the edge counts, not the level");
        }

        @Test
        void aFallingEdgeDoesNotCount() {
            var irq = new RecordingIRQ();
            var mapper = armedMapper(irq);

            mapper.ppuAddress(0x1000);
            for (var i = 0; i < LONG_LOW; i++) {
                mapper.ppuTick();
            }
            mapper.ppuAddress(0x0000);

            assertFalse(irq.asserted());
        }

        @Test
        void theDipIsMeasuredInDotsRatherThanInAccesses() {
            var irq = new RecordingIRQ();
            var mapper = armedMapper(irq);

            // A dozen reads of the nametable in the same dot: no time has passed, so the rise
            // that follows is still a glitch.
            mapper.ppuAddress(0x1000);
            for (var i = 0; i < 12; i++) {
                mapper.ppuAddress(0x2000);
            }
            mapper.ppuAddress(0x1000);

            assertFalse(irq.asserted());
        }

        /**
         * @return whether a rise after {@code dots} dots of A12 being low reaches the counter,
         * measured with a latch of zero so that a single clock is enough to fire.
         */
        private boolean clocksAfterLow(final int dots) {
            var irq = new RecordingIRQ();
            var mapper = armedMapper(irq);

            riseAfterLow(mapper, dots);

            return irq.asserted();
        }

        private Mapper4 armedMapper(final RecordingIRQ irq) {
            var mapper = mmc3();

            mapper.setIRQHandler(irq);
            mapper.prgWrite(0xC000, 0);  // a latch of zero fires on the first clock
            mapper.prgWrite(0xC001, 0);
            mapper.prgWrite(0xE001, 0);

            return mapper;
        }
    }

    /**
     * Drives A12 low, leaves it there for a while and raises it again: one scanline's worth of
     * address bus traffic, boiled down to the only part the chip is watching.
     */
    private static void riseAfterLow(final Mapper4 mapper, final int dots) {
        mapper.ppuAddress(0x0000);

        for (var i = 0; i < dots; i++) {
            mapper.ppuTick();
        }

        mapper.ppuAddress(0x1000);
    }

    /**
     * Writes one of the eight bank registers.
     *
     * @param select what to put in the bank select register: the register number in its low three
     *               bits, plus whichever of the PRG mode and CHR inversion bits the test wants.
     */
    private static void setBank(final Mapper4 mapper, final int select, final int bank) {
        mapper.prgWrite(0x8000, select);
        mapper.prgWrite(0x8001, bank);
    }

    /**
     * A 64KB PRG, 64KB CHR cartridge, both stamped with their bank numbers.
     */
    private static Mapper4 mmc3() {
        return new Mapper4(
                StampedROM.of(8, 0x2000), StampedROM.of(64, 0x400), Mirroring.VERTICAL
        );
    }

    /**
     * An /IRQ line that remembers where it was left.
     */
    private static final class RecordingIRQ implements IRQHandler {
        private boolean asserted;

        @Override
        public void setIRQLine(final boolean asserted) {
            this.asserted = asserted;
        }

        boolean asserted() {
            return asserted;
        }
    }
}
