package com.github.dimiro1.mynes.cart;

import com.github.dimiro1.mynes.InvalidNesFileException;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.UnsupportedMapperException;
import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mapper1;
import com.github.dimiro1.mynes.mappers.Mapper2;
import com.github.dimiro1.mynes.mappers.Mapper3;
import com.github.dimiro1.mynes.mappers.Mapper4;
import com.github.dimiro1.mynes.mappers.Mapper7;
import com.github.dimiro1.mynes.mappers.Mapper9;
import com.github.dimiro1.mynes.mappers.Mapper10;
import com.github.dimiro1.mynes.mappers.Mapper11;
import com.github.dimiro1.mynes.mappers.Mapper66;
import com.github.dimiro1.mynes.mappers.Mapper71;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class CartTests {
    @Test
    void load() throws IOException {
        var filename = "/nestest/nestest.nes";

        try (var stream = this.getClass().getResourceAsStream(filename)) {
            assertNotNull(stream);

            assertDoesNotThrow(() -> {
                var cart = Cart.load(stream.readAllBytes(), filename);

                assertEquals(filename, cart.filename());
                assertEquals("79e74c4c8e3218b332117c5043493f1e", md5(cart.prgROM()));
                assertEquals("4f094c912a70b39b38403b1f7a037579", md5(cart.chrROM()));
            });
        }
    }

    @Test
    void loadInvalid() throws IOException {
        var filename = "/nestest/nestest.log";

        try (InputStream stream = this.getClass().getResourceAsStream(filename)) {
            assertNotNull(stream);

            assertThrowsExactly(InvalidNesFileException.class, () -> Cart.load(stream.readAllBytes(), filename));
        }
    }

    /**
     * The mapper number is split across the high nibbles of flags 6 and 7. Reading it wrongly is
     * silent: an unsupported cart loads as mapper 0 and then misbehaves at run time instead of
     * being rejected here.
     */
    @Test
    void loadUnsupportedMapper() {
        var rom = synthesizeRom(0x50, 0x00);  // mapper 5, MMC5

        var thrown = assertThrowsExactly(
                UnsupportedMapperException.class,
                () -> Cart.load(rom, "mapper5.nes")
        );

        assertTrue(thrown.getMessage().contains("5"), "should name the mapper: " + thrown.getMessage());
    }

    @ParameterizedTest(name = "mapper ${0} loads as ${1}")
    @MethodSource("supportedMappers")
    void loadBuildsTheMapperTheHeaderNames(final int number, final Class<?> expected) {
        // Split across the two flag bytes the way a header does it, because past mapper 15 there
        // is no room for the number in flags 6 alone.
        var rom = synthesizeRom((number & 0x0F) << 4, number & 0xF0);

        var cart = Cart.load(rom, "mapper" + number + ".nes");

        assertInstanceOf(expected, cart.mapper());
    }

    private static Stream<Arguments> supportedMappers() {
        return Stream.of(
                arguments(0, Mapper0.class),
                arguments(1, Mapper1.class),
                arguments(2, Mapper2.class),
                arguments(3, Mapper3.class),
                arguments(4, Mapper4.class),
                arguments(7, Mapper7.class),
                arguments(9, Mapper9.class),
                arguments(10, Mapper10.class),
                arguments(11, Mapper11.class),
                arguments(66, Mapper66.class),
                arguments(71, Mapper71.class)
        );
    }

    @Test
    void loadReadsTheMapperNumberFromBothFlagBytes() {
        // Mapper 66 = 0x42: low nibble from flags 6, high nibble from flags 7. Reading only one
        // of them gives 2 or 64, and both of those are somebody else's cartridge.
        var rom = synthesizeRom(0x20, 0x40);

        var cart = Cart.load(rom, "mapper66.nes");

        assertEquals(66, cart.mapperNumber());
        assertInstanceOf(Mapper66.class, cart.mapper());
    }

    /**
     * NES 2.0 announces itself in bits 2 and 3 of flags 7, and then byte 12 says which console the
     * cartridge was made for. It is the only one of the three ways of asking that has more than two
     * answers, and the only one that is usually filled in.
     */
    @ParameterizedTest(name = "NES 2.0 byte 12 = ${0} is ${1}")
    @MethodSource("nes20Timings")
    void theRegionComesFromByte12OfANES20Header(final int byte12, final Cart.Timing expected) {
        var rom = synthesizeRom(0x00, 0x08);
        rom[12] = (byte) byte12;

        assertEquals(expected, Cart.load(rom, "timing.nes").timing());
    }

    private static Stream<Arguments> nes20Timings() {
        return Stream.of(
                arguments(0, Cart.Timing.NTSC),
                arguments(1, Cart.Timing.PAL),
                arguments(2, Cart.Timing.MULTI_REGION),
                arguments(3, Cart.Timing.DENDY),
                // Only the bottom two bits are the timing; the rest of the byte is not ours.
                arguments(0xFD, Cart.Timing.PAL)
        );
    }

    @Test
    void aCartridgeThatRunsOnEitherMachineIsRunAsNTSC() {
        var rom = synthesizeRom(0x00, 0x08);
        rom[12] = 2;

        var cart = Cart.load(rom, "multi.nes");

        assertEquals(Cart.Timing.MULTI_REGION, cart.timing(), "what the header said");
        assertEquals(Region.NTSC, cart.region(), "and what that comes to");
    }

    @Test
    void aDendyCartridgeIsRunAsPAL() {
        // Dendy is not modelled. Of the two machines here it is much the nearer: the picture and
        // the frame rate are PAL's, and only the CPU rate is out.
        var rom = synthesizeRom(0x00, 0x08);
        rom[12] = 3;

        assertEquals(Region.PAL, Cart.load(rom, "dendy.nes").region());
    }

    @Test
    void plainINESIsAskedAboutBytes9And10() {
        var byNine = synthesizeRom(0x00, 0x00);
        byNine[9] = 1;

        assertEquals(Cart.Timing.PAL, Cart.load(byNine, "flags9.nes").timing());

        var byTen = synthesizeRom(0x00, 0x00);
        byTen[10] = 2;

        assertEquals(Cart.Timing.PAL, Cart.load(byTen, "flags10.nes").timing());

        var eitherMachine = synthesizeRom(0x00, 0x00);
        eitherMachine[10] = 1;

        assertEquals(Cart.Timing.MULTI_REGION, Cart.load(eitherMachine, "dual.nes").timing());
    }

    @Test
    void aHeaderWithAnythingWrittenAcrossItsTailIsNotBelieved() {
        // Dumps from the 1990s wrote the ripper's name over the end of the header, and a byte of
        // somebody's handle lands in byte 9 as readily as anywhere else. A cartridge declared PAL
        // by the letter "P" of a signature would be a mystifying thing to debug.
        var rom = synthesizeRom(0x00, 0x00);
        rom[9] = 1;
        rom[13] = 'D';
        rom[14] = 'i';
        rom[15] = 'z';

        assertEquals(Cart.Timing.UNSTATED, Cart.load(rom, "signed.nes").timing());
        assertEquals(Region.NTSC, Cart.load(rom, "signed.nes").region());
    }

    @Test
    void aHeaderThatSaysNothingIsNTSC() {
        // Which is most of them, and the reason there is a way to say otherwise by hand.
        var cart = Cart.load(synthesizeRom(0x00, 0x00), "quiet.nes");

        assertEquals(Cart.Timing.UNSTATED, cart.timing());
        assertEquals(Region.NTSC, cart.region());
    }

    @Test
    void whichHeaderWasReadIsRecorded() {
        assertEquals(Cart.Format.INES, Cart.load(synthesizeRom(0x00, 0x00), "old.nes").format());
        assertEquals(Cart.Format.NES20, Cart.load(synthesizeRom(0x00, 0x08), "new.nes").format());
        // Bits 2-3 reading 1 or 3 is not NES 2.0, whatever else is in the byte.
        assertEquals(Cart.Format.INES, Cart.load(synthesizeRom(0x00, 0x04), "odd.nes").format());
    }

    /**
     * Byte 8 of a NES 2.0 header carries four more bits of mapper number, which is how the format
     * got past 255. None of those mappers is supported, so the proof that the bits were read is
     * the number in the refusal.
     */
    @Test
    void nes20HasFourMoreBitsOfMapperNumberInByte8() {
        var rom = synthesizeRom(0x10, 0x08);
        rom[8] = 0x01;

        var thrown = assertThrowsExactly(
                UnsupportedMapperException.class, () -> Cart.load(rom, "mapper257.nes"));

        assertTrue(thrown.getMessage().contains("257"), thrown.getMessage());
    }

    @Test
    void theSubmapperIsTheTopOfByte8UnderNES20AndNothingUnderINES() {
        var nes20 = synthesizeRom(0x10, 0x08);
        nes20[8] = 0x50;

        assertEquals(5, Cart.load(nes20, "serom.nes").submapper());

        // The same byte under iNES counts PRG RAM, and there is no submapper to read.
        var ines = synthesizeRom(0x10, 0x00);
        ines[8] = 0x50;

        assertEquals(0, Cart.load(ines, "ines.nes").submapper());
    }

    @Test
    void byte9AddsFourBitsToTheROMSizeUnderNES20() {
        var rom = new byte[16 + 257 * 0x4000];
        System.arraycopy(synthesizeRom(0x00, 0x08), 0, rom, 0, 16);
        rom[9] = 0x01;

        assertEquals(257 * 0x4000, Cart.load(rom, "big.nes").prgROM().length);
    }

    @Test
    void anFInByte9MakesByte4AnExponentAndAMultiplier() {
        // 2^14 * 3: a 48KB ROM, which no whole number of 16KB banks says.
        var rom = new byte[16 + 0xC000];
        System.arraycopy(synthesizeRom(0x00, 0x08), 0, rom, 0, 16);
        rom[4] = (byte) (14 << 2 | 1);
        rom[9] = 0x0F;

        assertEquals(0xC000, Cart.load(rom, "odd-size.nes").prgROM().length);
    }

    @Test
    void aHeaderNamingMoreROMThanAnyFileHoldsIsRefusedRatherThanAllocated() {
        var rom = synthesizeRom(0x00, 0x08);
        rom[4] = (byte) (40 << 2);
        rom[9] = 0x0F;

        assertThrowsExactly(InvalidNesFileException.class, () -> Cart.load(rom, "vast.nes"));
    }

    @Test
    void theRAMSizesComeOutOfBytes10And11AsShiftCounts() {
        var rom = synthesizeRom(0x02, 0x08);
        rom[10] = 0x71;  // 128 bytes of PRG RAM below 8KB of PRG NVRAM
        rom[11] = 0x07;  // 8KB of CHR RAM

        var ram = Cart.load(rom, "sizes.nes").ram();

        assertEquals(new Cart.RAM(128, 8192, 8192, 0), ram);
        assertEquals(8320, ram.prg(), "the two PRG halves are one space to the mapper");
    }

    @Test
    void aZeroShiftCountIsNoRAMRatherThanSixtyFourBytes() {
        assertEquals(Cart.RAM.NONE, Cart.load(synthesizeRom(0x00, 0x08), "none.nes").ram());
    }

    /**
     * iNES has one number for all of this, in units of 8KB, and a zero there was defined to mean
     * one unit because the byte arrived after most of the library had been headered without it.
     */
    @Test
    void plainINESCountsPRGRAMInByte8() {
        var battery = synthesizeRom(0x02, 0x00);
        battery[8] = 4;

        assertEquals(new Cart.RAM(0, 0x8000, 0x2000, 0), Cart.load(battery, "sxrom.nes").ram(),
                "on the battery side, because the header says there is one");

        var scratch = synthesizeRom(0x00, 0x00);
        scratch[8] = 2;

        assertEquals(new Cart.RAM(0x4000, 0, 0x2000, 0), Cart.load(scratch, "work.nes").ram());

        assertEquals(0x2000, Cart.load(synthesizeRom(0x00, 0x00), "blank.nes").ram().prg(),
                "zero means one unit");
    }

    @Test
    void butNotOutOfASignedHeader() {
        var rom = synthesizeRom(0x00, 0x00);
        rom[8] = 's';
        rom[13] = 'D';
        rom[14] = 'i';
        rom[15] = 'z';

        assertEquals(0x2000, Cart.load(rom, "signed.nes").ram().prg(),
                "a letter of somebody's handle is not nine hundred kilobytes of RAM");
    }

    @Test
    void anMMC1BoardIsGivenTheRAMTheHeaderSays() {
        var sxrom = synthesizeRom(0x12, 0x08);
        sxrom[10] = (byte) 0x90;  // 32KB of PRG NVRAM

        assertEquals(0x8000, Cart.load(sxrom, "sxrom.nes").mapper().prgRAM().length);

        var quiet = synthesizeRom(0x10, 0x08);

        assertEquals(0x2000, Cart.load(quiet, "slrom.nes").mapper().prgRAM().length,
                "and the 8KB every board here has when it says nothing");
    }

    @Test
    void mapper155IsAnMMC1() {
        var rom = synthesizeRom((155 & 0x0F) << 4, 155 & 0xF0);

        var cart = Cart.load(rom, "mmc1a.nes");

        assertEquals(155, cart.mapperNumber());
        assertInstanceOf(Mapper1.class, cart.mapper());
    }

    /**
     * Builds a minimal but valid iNES image: a header, one 16KB PRG bank and no CHR banks.
     */
    private byte[] synthesizeRom(final int flags6, final int flags7) {
        var rom = new byte[16 + 0x4000];

        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;  // one PRG bank
        rom[5] = 0;  // no CHR banks
        rom[6] = (byte) flags6;
        rom[7] = (byte) flags7;

        return rom;
    }

    private String md5(byte[] data) {
        try {
            var md5 = MessageDigest.getInstance("MD5");
            return String.format("%x", new BigInteger(1, md5.digest(data)));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
