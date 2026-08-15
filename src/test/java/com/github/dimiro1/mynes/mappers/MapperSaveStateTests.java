package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Every board's registers, out through {@link StateIO} and back into a machine that has just been
 * switched on.
 * <p>
 * {@code SaveStateCompletenessTests} is the usual backstop for this, and it cannot be here: it
 * walks one running console, and that console is loaded from an NROM test ROM, so the only mapper
 * its reflection ever reaches is {@link Mapper0}. Everything else is covered by whichever ROM
 * happens to be vendored -- MMC3 through the blargg suite, nothing at all for the six boards
 * added alongside this file. So the fields are driven through the same registers a game would
 * write, and the assertion is made on what the CPU and PPU would read back rather than on the
 * fields themselves.
 */
class MapperSaveStateTests {
    /**
     * A board, and the writes that put it somewhere a freshly built one is not.
     *
     * @param name    what to call it when the assertion fails.
     * @param build   makes one at power up.
     * @param arrange writes the registers.
     * @param prgAddresses  addresses to compare through {@code prgRead}.
     * @param charAddresses addresses to compare through {@code charRead}.
     */
    private record Board(
            String name,
            Supplier<Mapper> build,
            Consumer<Mapper> arrange,
            int[] prgAddresses,
            int[] charAddresses) {
    }

    @ParameterizedTest(name = "${0}")
    @MethodSource("boards")
    void everyRegisterComesBackTheWayItWentIn(final String name, final Board board) {
        var saved = board.build().get();
        board.arrange().accept(saved);

        var out = StateIO.writing();
        saved.serialize(out);

        var loaded = board.build().get();
        loaded.serialize(StateIO.reading(out.written()));

        for (var address : board.prgAddresses()) {
            assertEquals(
                    saved.prgRead(address),
                    loaded.prgRead(address),
                    () -> name + " reads a different bank at $" + Integer.toHexString(address));
        }

        for (var address : board.charAddresses()) {
            assertEquals(
                    saved.charRead(address),
                    loaded.charRead(address),
                    () -> name + " shows a different tile at $" + Integer.toHexString(address));
        }

        assertEquals(saved.mirroring(), loaded.mirroring(), name + " wires the nametables differently");
    }

    /**
     * The other half of the claim: without the round trip the two machines disagree, so the test
     * above is evidence rather than a coincidence about power up values.
     */
    @ParameterizedTest(name = "${0}")
    @MethodSource("boards")
    void andWouldNotHaveWithoutTheRoundTrip(final String name, final Board board) {
        var saved = board.build().get();
        board.arrange().accept(saved);

        var fresh = board.build().get();

        var same = true;

        for (var address : board.prgAddresses()) {
            same &= saved.prgRead(address) == fresh.prgRead(address);
        }

        for (var address : board.charAddresses()) {
            same &= saved.charRead(address) == fresh.charRead(address);
        }

        same &= saved.mirroring() == fresh.mirroring();

        assertNotEquals(true, same, name + " was never moved away from where it powers up");
    }

    @Test
    void cartridgeRamTravelsWithTheBoardThatHasIt() {
        var saved = new Mapper10(StampedROM.of(8, 0x4000), StampedROM.of(32, 0x1000), Mirroring.VERTICAL);
        saved.prgRAMWrite(0x6000, 0x11);
        saved.prgRAMWrite(0x7FFF, 0x22);

        var out = StateIO.writing();
        saved.serialize(out);

        var loaded = new Mapper10(StampedROM.of(8, 0x4000), StampedROM.of(32, 0x1000), Mirroring.VERTICAL);
        loaded.serialize(StateIO.reading(out.written()));

        assertEquals(0x11, loaded.prgRAMRead(0x6000));
        assertEquals(0x22, loaded.prgRAMRead(0x7FFF));
    }

    @Test
    void andSoDoesTheAddressAWaitingLatchHasNotSeenYet() {
        var saved = new Mapper9(StampedROM.of(16, 0x2000), StampedROM.of(32, 0x1000), Mirroring.VERTICAL);
        saved.prgWrite(0xB000, 3);
        saved.prgWrite(0xC000, 7);

        // Saved between the fetch that trips the latch and the one that sees it move, which is
        // where a frame boundary can perfectly well fall.
        saved.ppuAddress(0x0FE8);

        var out = StateIO.writing();
        saved.serialize(out);

        var loaded = new Mapper9(StampedROM.of(16, 0x2000), StampedROM.of(32, 0x1000), Mirroring.VERTICAL);
        loaded.serialize(StateIO.reading(out.written()));

        assertEquals(3, loaded.charRead(0x0000), "still waiting, as it was");

        loaded.ppuAddress(0x2000);

        assertEquals(7, loaded.charRead(0x0000), "and the switch it was owed still happens");
    }

    private static Stream<Arguments> boards() {
        return Stream.of(
                board("AxROM", () -> new Mapper7(prg(4, 0x8000), none(), Mirroring.HORIZONTAL),
                        m -> m.prgWrite(0x8000, 0x12),
                        new int[]{0x8000, 0xFFFF}, new int[]{}),
                board("MMC2", () -> new Mapper9(prg(16, 0x2000), chr(32, 0x1000), Mirroring.VERTICAL),
                        m -> {
                            m.prgWrite(0xA000, 5);
                            m.prgWrite(0xB000, 1);
                            m.prgWrite(0xC000, 2);
                            m.prgWrite(0xD000, 3);
                            m.prgWrite(0xE000, 4);
                            m.prgWrite(0xF000, 1);
                            m.ppuAddress(0x0FE8);
                            m.ppuAddress(0x1FE8);
                            m.ppuAddress(0x2000);
                        },
                        new int[]{0x8000, 0xA000, 0xFFFF}, new int[]{0x0000, 0x1000}),
                board("MMC4", () -> new Mapper10(prg(8, 0x4000), chr(32, 0x1000), Mirroring.VERTICAL),
                        m -> {
                            m.prgWrite(0xA000, 5);
                            m.prgWrite(0xB000, 1);
                            m.prgWrite(0xC000, 2);
                            m.prgWrite(0xD000, 3);
                            m.prgWrite(0xE000, 4);
                            m.prgWrite(0xF000, 1);
                            m.ppuAddress(0x0FE8);
                            m.ppuAddress(0x1FE8);
                            m.ppuAddress(0x2000);
                        },
                        new int[]{0x8000, 0xFFFF}, new int[]{0x0000, 0x1000}),
                board("Color Dreams", () -> new Mapper11(prg(4, 0x8000), chr(16, 0x2000), Mirroring.VERTICAL),
                        m -> m.prgWrite(0x8000, 0x93),
                        new int[]{0x8000, 0xFFFF}, new int[]{0x0000, 0x1FFF}),
                board("GxROM", () -> new Mapper66(prg(4, 0x8000), chr(4, 0x2000), Mirroring.VERTICAL),
                        m -> m.prgWrite(0x8000, 0x32),
                        new int[]{0x8000, 0xFFFF}, new int[]{0x0000, 0x1FFF}),
                board("Camerica", () -> new Mapper71(prg(4, 0x4000), none(), Mirroring.HORIZONTAL),
                        m -> {
                            m.prgWrite(0xC000, 2);
                            m.prgWrite(0x9000, 0x10);
                        },
                        new int[]{0x8000, 0xC000}, new int[]{}));
    }

    private static Arguments board(
            final String name,
            final Supplier<Mapper> build,
            final Consumer<Mapper> arrange,
            final int[] prgAddresses,
            final int[] charAddresses) {
        return arguments(name, new Board(name, build, arrange, prgAddresses, charAddresses));
    }

    private static byte[] prg(final int banks, final int bankSize) {
        return StampedROM.of(banks, bankSize);
    }

    private static byte[] chr(final int banks, final int bankSize) {
        return StampedROM.of(banks, bankSize);
    }

    private static byte[] none() {
        return new byte[0];
    }
}
