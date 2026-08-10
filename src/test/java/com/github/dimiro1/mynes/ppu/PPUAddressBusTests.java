package com.github.dimiro1.mynes.ppu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the cartridge sees on the PPU's address bus.
 * <p>
 * The bus is not private to the PPU: all fourteen lines run out to the cartridge connector, and
 * MMC3 counts scanlines by watching one of them. So which addresses appear there, and when, is
 * behaviour a mapper depends on rather than an implementation detail.
 */
class PPUAddressBusTests extends PPUFixture {
    private RecordingMapper recorder;

    @BeforeEach
    void setUp() {
        recorder = new RecordingMapper();
        createPPU(recorder);
    }

    @Nested
    @DisplayName("$2006")
    class AddressRegister {
        @Test
        void theSecondWritePutsTheWholeAddressOnTheBus() {
            ppu.read(PPUSTATUS);  // settle the shared write latch
            ppu.write(PPUADDR, 0x21);

            assertEquals(List.of(), recorder.addresses(), "half an address drives nothing");

            ppu.write(PPUADDR, 0x08);

            assertEquals(List.of(0x2108), recorder.addresses());
        }

        @Test
        void theAddressAppearsWithNoAccessGoingWithIt() {
            setVRAMAddress(0x1234);
            recorder.clear();

            // A second pair of writes, and still nothing has been read or written.
            setVRAMAddress(0x0FFF);

            assertEquals(List.of(0x0FFF), recorder.addresses());
        }
    }

    @Nested
    @DisplayName("$2007")
    class DataRegister {
        @Test
        void readsPutTheAddressOnTheBusAndThenTheOneTheIncrementLeaves() {
            setVRAMAddress(0x2000);
            recorder.clear();

            ppu.read(PPUDATA);

            assertEquals(
                    List.of(0x2000, 0x2001), recorder.addresses(),
                    "outside rendering nothing else is driving the bus, so it holds the address"
            );
        }

        @Test
        void writesDoTheSame() {
            setVRAMAddress(0x0123);
            recorder.clear();

            ppu.write(PPUDATA, 0x42);

            assertEquals(List.of(0x0123, 0x0124), recorder.addresses());
        }

        @Test
        void theIncrementIsHowAGameMakesA12RiseWithThePictureOff() {
            // One read with the address just short of $1000 and the increment carries it across,
            // which is exactly what blargg's 3-A12_clocking does to the scanline counter.
            setVRAMAddress(0x0FFF);
            recorder.clear();

            ppu.read(PPUDATA);

            assertEquals(List.of(0x0FFF, 0x1000), recorder.addresses());
        }

        @Test
        void aPaletteReadShowsThePaletteAddressRatherThanTheNametableThatAnswers() {
            // The byte comes back from the nametable underneath, but the address on the bus is
            // the one the program asked for -- and it has A12 high, which is what a mapper
            // counting A12 rises cares about.
            setVRAMAddress(0x3F05);
            recorder.clear();

            ppu.read(PPUDATA);

            assertEquals(List.of(0x3F05, 0x3F06), recorder.addresses());
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {
        @Test
        void walksTheNametableAndThePatternTableTheControlRegisterChose() {
            enableRendering(0x10, 0x08);  // background patterns at $1000, background shown
            runTo(0, 0);
            recorder.clear();

            run(8);  // one whole tile fetch: dots 0 to 7 of the first visible line

            assertEquals(
                    // $2002 rather than $2000: the last two fetches of the previous line have
                    // already moved coarse X on twice.
                    List.of(0x2002, 0x23C0, 0x1000, 0x1008), recorder.addresses(),
                    "nametable, attribute, then the two halves of the pattern"
            );
        }

        @Test
        void thePatternFetchesFollowTheOtherTableToo() {
            enableRendering(0x00, 0x08);  // background patterns at $0000
            runTo(0, 0);
            recorder.clear();

            run(8);

            assertEquals(List.of(0x2002, 0x23C0, 0x0000, 0x0008), recorder.addresses());
        }

        @Test
        void spriteFetchesReachTheSpriteTable() {
            enableRendering(0x08, 0x10);  // sprite patterns at $1000, sprites shown
            runTo(0, 257);
            recorder.clear();

            run(8);  // one sprite unit's slot: two idle nametable reads, then the pattern

            assertEquals(
                    // OAM is all zeros at power on, so every slot found on line 0 is tile 0 of
                    // the sprite pattern table.
                    List.of(0x2000, 0x2000, 0x1000, 0x1008), recorder.addresses(),
                    "the two halves of a sprite pattern arrive in one dot"
            );
        }

        /**
         * Points the pattern tables where the test wants them and turns rendering on, then runs
         * far enough that the two dot delay on a $2001 write has passed.
         */
        private void enableRendering(final int ctrl, final int mask) {
            ppu.write(PPUCTRL, ctrl);
            ppu.write(PPUMASK, mask);
            run(4);
        }
    }

    @Nested
    @DisplayName("the dot clock")
    class DotClock {
        @Test
        void everyDotIsPassedOn() {
            run(1000);

            assertEquals(1000, recorder.dots(), "a mapper timing the bus needs every dot");
        }

        @Test
        void keepsRunningWithTheRenderingSwitchedOff() {
            ppu.write(PPUMASK, 0x00);
            run(500);

            assertTrue(recorder.dots() >= 500);
        }
    }

    /**
     * A cartridge that writes down every address it is shown.
     */
    private static final class RecordingMapper extends StubMapper {
        private final List<Integer> addresses = new ArrayList<>();
        private int dots;

        @Override
        public void ppuAddress(final int address) {
            addresses.add(address);
        }

        @Override
        public void ppuTick() {
            dots++;
        }

        List<Integer> addresses() {
            return addresses;
        }

        int dots() {
            return dots;
        }

        void clear() {
            addresses.clear();
        }
    }
}
