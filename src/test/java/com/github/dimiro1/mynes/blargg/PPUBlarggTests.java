package com.github.dimiro1.mynes.blargg;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * Test suite for Blargg's NES PPU test ROMs.
 * <p>
 * Split from {@link BlarggTests} because the PPU ROMs are slower -- some of them spend seconds of
 * emulated time measuring things a frame at a time -- and because half of them predate the $6000
 * protocol and report a numeric code in zero page instead. {@link BlarggRunner} covers both.
 * <p>
 * The readme that came with each suite is vendored next to the ROMs and is what a failure code
 * has to be looked up in.
 *
 * @see <a href="https://github.com/christopherpow/nes-test-roms">Blargg's Test ROMs</a>
 */
public class PPUBlarggTests {
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    /**
     * The zero page byte the 2005 era sprite suites keep their result code in.
     */
    private static final int SPRITE_TEST_RESULT = 0x00F8;

    /**
     * The zero page byte the 2005 era PPU suite keeps its result code in.
     */
    private static final int PPU_TEST_RESULT = 0x00F0;

    /**
     * How many frames of emulated time a result code ROM gets before it is called stuck. The
     * slowest of them takes a handful of seconds of emulated time; this is a generous fifteen.
     */
    private static final long FRAME_BUDGET = 900;

    @Nested
    class VBlankAndNMI {
        /**
         * The singles rather than the combined {@code ppu_vbl_nmi.nes}, which is an MMC1 cart.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/ppu-vbl-nmi/01-vbl_basics.nes",
                "/ppu-vbl-nmi/02-vbl_set_time.nes",
                "/ppu-vbl-nmi/03-vbl_clear_time.nes",
                "/ppu-vbl-nmi/04-nmi_control.nes",
                "/ppu-vbl-nmi/05-nmi_timing.nes",
                "/ppu-vbl-nmi/06-suppression.nes",
                "/ppu-vbl-nmi/07-nmi_on_timing.nes",
                "/ppu-vbl-nmi/08-nmi_off_timing.nes",
                "/ppu-vbl-nmi/09-even_odd_frames.nes",
                "/ppu-vbl-nmi/10-even_odd_timing.nes",
        })
        void vblankAndNMI(final String filename) throws IOException {
            BlarggRunner.runStatusProtocol(filename, TIMEOUT, Set.of());
        }
    }

    @Nested
    class Memory {
        @ParameterizedTest
        @ValueSource(strings = {
                "/oam/oam_read.nes",
                "/oam/oam_stress.nes",
                "/ppu-open-bus/ppu_open_bus.nes",
                "/ppu-read-buffer/test_ppu_read_buffer.nes",
        })
        void memoryInterface(final String filename) throws IOException {
            BlarggRunner.runStatusProtocol(filename, TIMEOUT, Set.of());
        }

        /**
         * The 2005 suite: palette RAM mirroring, VRAM access through $2006/$2007, sprite RAM
         * through $2003/$2004/$4014, and how late in VBlank the flag is still readable.
         * <p>
         * {@code power_up_palette.nes} is deliberately not vendored: it compares the palette
         * against the contents of blargg's own console at power on, which he says are probably
         * unique to it.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/ppu-tests-2005/palette_ram.nes",
                "/ppu-tests-2005/vram_access.nes",
                "/ppu-tests-2005/sprite_ram.nes",
                "/ppu-tests-2005/vbl_clear_time.nes",
        })
        void ppuTests2005(final String filename) throws IOException {
            BlarggRunner.runResultCode(filename, PPU_TEST_RESULT, FRAME_BUDGET, TIMEOUT);
        }
    }

    @Nested
    class SpriteZeroHit {
        @ParameterizedTest
        @ValueSource(strings = {
                "/ppu-sprite-hit/01-basics.nes",
                "/ppu-sprite-hit/02-alignment.nes",
                "/ppu-sprite-hit/03-corners.nes",
                "/ppu-sprite-hit/04-flip.nes",
                "/ppu-sprite-hit/05-left_clip.nes",
                "/ppu-sprite-hit/06-right_edge.nes",
                "/ppu-sprite-hit/07-screen_bottom.nes",
                "/ppu-sprite-hit/08-double_height.nes",
                "/ppu-sprite-hit/09-timing_basics.nes",
                "/ppu-sprite-hit/10-timing_order.nes",
                "/ppu-sprite-hit/11-edge_timing.nes",
        })
        void spriteZeroHit(final String filename) throws IOException {
            BlarggRunner.runResultCode(filename, SPRITE_TEST_RESULT, FRAME_BUDGET, TIMEOUT);
        }
    }

    @Nested
    class SpriteOverflow {
        @ParameterizedTest
        @ValueSource(strings = {
                "/ppu-sprite-overflow/01-basics.nes",
                "/ppu-sprite-overflow/02-details.nes",
                "/ppu-sprite-overflow/03-timing.nes",
                "/ppu-sprite-overflow/04-obscure.nes",
                "/ppu-sprite-overflow/05-emulator.nes",
        })
        void spriteOverflow(final String filename) throws IOException {
            BlarggRunner.runResultCode(filename, SPRITE_TEST_RESULT, FRAME_BUDGET, TIMEOUT);
        }
    }
}
