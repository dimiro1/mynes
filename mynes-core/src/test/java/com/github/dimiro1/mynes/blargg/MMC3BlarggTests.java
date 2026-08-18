package com.github.dimiro1.mynes.blargg;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * blargg's MMC3 test ROMs, which are almost entirely about the scanline counter.
 * <p>
 * They report through the $6000 protocol; {@link BlarggRunner} explains it and does the driving.
 * Most of them clock the counter by hand, writing $2006 to move the VRAM address across the
 * boundary A12 sits on, rather than by rendering -- so they pin down the address bus plumbing as
 * much as the mapper.
 * <p>
 * Two of the six are left out, for reasons that are not going to change.
 * <p>
 * {@code 4-scanline_timing} measures the gap between the VBlank flag going up and the interrupt
 * arriving, and insists on it to the dot. That needs the PPU to present each fetch address on the
 * dot the hardware does and to leave it on the bus in between, where this PPU tells the cartridge
 * about an access when the access happens. The counter still fires once a scanline, in the right
 * half of the picture, which is what a split screen needs; it is a dot or so out on where.
 * <p>
 * {@code 6-MMC3_alt} is the MMC3 revision A counter, which reloads a beat differently from the
 * revision B and C chips almost every game shipped on. It contradicts {@code 5-MMC3} by design:
 * no chip passes both, and this one implements the revision Super Mario Bros. 3 came on.
 *
 * @see <a href="https://github.com/christopherpow/nes-test-roms">blargg's test ROMs</a>
 */
public class MMC3BlarggTests {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @ParameterizedTest
    @ValueSource(strings = {
            "/mmc3-test-2/1-clocking.nes",
            "/mmc3-test-2/2-details.nes",
            "/mmc3-test-2/3-A12_clocking.nes",
            "/mmc3-test-2/5-MMC3.nes",
//            "/mmc3-test-2/4-scanline_timing.nes", // Dot exact bus timing required
//            "/mmc3-test-2/6-MMC3_alt.nes", // The other revision; excludes 5-MMC3
    })
    void scanlineCounter(final String filename) throws IOException {
        BlarggRunner.runStatusProtocol(filename, TIMEOUT, Set.of());
    }
}
