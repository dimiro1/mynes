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
 * One of the six is left out, for a reason that is not going to change: {@code 6-MMC3_alt} is the
 * MMC3 revision A counter, which reloads a beat differently from the revision B and C chips almost
 * every game shipped on. It contradicts {@code 5-MMC3} by design: no chip passes both, and this one
 * implements the revision Super Mario Bros. 3 came on.
 * <p>
 * {@code 4-scanline_timing} is the strictest of the five. It measures the gap between the VBlank
 * flag going up and the interrupt arriving and insists on it to the dot, twelve times down the
 * picture and in both of the ways a game can arrange its pattern tables -- so it holds the PPU's
 * fetch schedule, the two dots each of those fetches takes, and
 * {@link com.github.dimiro1.mynes.mappers.Mapper4}'s filter against each other all at once.
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
            "/mmc3-test-2/4-scanline_timing.nes",
            "/mmc3-test-2/5-MMC3.nes",
//            "/mmc3-test-2/6-MMC3_alt.nes", // The other revision; excludes 5-MMC3
    })
    void scanlineCounter(final String filename) throws IOException {
        BlarggRunner.runStatusProtocol(filename, TIMEOUT, Set.of());
    }
}
