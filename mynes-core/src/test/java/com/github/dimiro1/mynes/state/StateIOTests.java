package com.github.dimiro1.mynes.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one list of fields, checked in both directions.
 * <p>
 * Nothing here builds a console. The two properties worth pinning down are that what goes in comes
 * out, and that a chunk which runs out part way leaves the rest of the machine alone -- and the
 * second is what the whole tolerance story rests on, so it is worth proving on bytes before there
 * is a PPU in the way.
 */
class StateIOTests {

    private enum Step { FIRST, SECOND, THIRD }

    @Test
    void everyKindOfFieldSurvivesTheRoundTrip() {
        var out = StateIO.writing();

        out.bool(true);
        out.bool(false);
        out.u8(0xA5);
        out.u16(0xBEEF);
        out.u32(0x12345678);
        out.u64(0x0123456789ABCDEFL);
        out.f64(Math.PI);
        out.enumeration(Step.SECOND, Step.class);

        var in = StateIO.reading(out.written());

        assertTrue(in.bool(false));
        assertFalse(in.bool(true));
        assertEquals(0xA5, in.u8(0));
        assertEquals(0xBEEF, in.u16(0));
        assertEquals(0x12345678, in.u32(0));
        assertEquals(0x0123456789ABCDEFL, in.u64(0));
        assertEquals(Math.PI, in.f64(0), "bit for bit, not near enough");
        assertEquals(Step.SECOND, in.enumeration(Step.FIRST, Step.class));
    }

    @Test
    void aNegativeCycleCountIsStillTheSameNumberComingBack() {
        var out = StateIO.writing();
        out.u64(-1L);

        assertEquals(-1L, StateIO.reading(out.written()).u64(0));
    }

    @Test
    void theThreeKindsOfArrayComeBackAsTheyWent() {
        var out = StateIO.writing();

        out.bytes(new byte[]{1, 2, (byte) 0xFF});
        out.bytes(new int[]{0x10, 0x20, 0xFF});
        out.words(new int[]{0, 511, 0x1FF});
        out.longs(new long[]{0, -1, 1234567890123L});

        var in = StateIO.reading(out.written());

        var signed = new byte[3];
        var unsigned = new int[3];
        var words = new int[3];
        var longs = new long[3];

        in.bytes(signed);
        in.bytes(unsigned);
        in.words(words);
        in.longs(longs);

        assertArrayEquals(new byte[]{1, 2, (byte) 0xFF}, signed);
        assertArrayEquals(new int[]{0x10, 0x20, 0xFF}, unsigned, "each element holds one byte");
        assertArrayEquals(new int[]{0, 511, 0x1FF}, words, "and each of these holds two");
        assertArrayEquals(new long[]{0, -1, 1234567890123L}, longs);
    }

    /**
     * The property the whole format's tolerance rests on. A build that has since gained a field
     * reads a file written before it existed, runs off the end of the chunk, and has to leave that
     * field holding what the machine already had -- which on a freshly built console is its
     * power-on default. Zero would be actively wrong for several of them.
     */
    @Test
    void aFieldTheChunkDoesNotReachKeepsWhatItHad() {
        var out = StateIO.writing();
        out.u8(0x11);

        var in = StateIO.reading(out.written());

        assertEquals(0x11, in.u8(0x99), "this one is in the file");
        assertEquals(0x99, in.u8(0x99), "and this one was added later");
        assertEquals(1, in.u8(1), "a noise shift register that must never read back as zero");
        assertTrue(in.bool(true), "a DMC that powers on silent");
        assertEquals(Math.E, in.f64(Math.E), "a filter's accumulated state");
        assertEquals(Step.THIRD, in.enumeration(Step.THIRD, Step.class));
    }

    @Test
    void aValueStraddlingTheEndOfTheChunkIsNotHalfRead() {
        var out = StateIO.writing();
        out.u8(0x11);

        var in = StateIO.reading(out.written());

        assertEquals(0x2222, in.u16(0x2222), "one byte left is not enough for two");
        assertEquals(0x11, in.u8(0), "and the byte that was there is still unread");
    }

    @Test
    void anArrayLongerThanTheChunkIsOnlyPartlyFilled() {
        var out = StateIO.writing();
        out.bytes(new byte[]{7, 7});

        var array = new byte[]{1, 2, 3, 4};
        StateIO.reading(out.written()).bytes(array);

        assertArrayEquals(new byte[]{7, 7, 3, 4}, array, "the tail keeps what it held");
    }

    @Test
    void anOrdinalNamingNoConstantLeavesTheValueAlone() {
        var out = StateIO.writing();
        out.u8(99);

        assertEquals(
                Step.SECOND,
                StateIO.reading(out.written()).enumeration(Step.SECOND, Step.class));
    }

    @Test
    void itKnowsWhichWayItIsPointing() {
        assertTrue(StateIO.writing().saving());
        assertFalse(StateIO.reading(new byte[0]).saving());
    }

    @Test
    void multiByteValuesAreBigEndian() {
        var out = StateIO.writing();
        out.u16(0x1234);

        assertArrayEquals(
                new byte[]{0x12, 0x34},
                out.written(),
                "so that a hex dump of a state reads the way the numbers do");
    }
}
