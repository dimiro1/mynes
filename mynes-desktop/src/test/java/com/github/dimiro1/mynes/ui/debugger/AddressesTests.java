package com.github.dimiro1.mynes.ui.debugger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AddressesTests {
    @Test
    void aBareNumberIsHexadecimalBecauseEverythingAroundItIs() {
        assertEquals(0x6000, Addresses.parse("6000"));
    }

    @Test
    void aDollarOrAZeroXPrefixIsAccepted() {
        assertEquals(0xC000, Addresses.parse("$C000"));
        assertEquals(0xC000, Addresses.parse("0xC000"));
        assertEquals(0xC000, Addresses.parse("0XC000"));
    }

    @Test
    void whitespaceAroundItIsIgnored() {
        assertEquals(0x0300, Addresses.parse("  $0300 "));
    }

    @Test
    void anAddressIsSixteenBits() {
        assertEquals(0x0300, Addresses.parse("10300"));
    }

    @Test
    void aWordThatIsNotAnAddressSaysSo() {
        var e = assertThrows(IllegalArgumentException.class, () -> Addresses.parse("start"));

        assertEquals("\"start\" is not an address.", e.getMessage());
    }

    @Test
    void anEntryWithoutAConditionHasNone() {
        var entry = Addresses.parseEntry("$8082");

        assertEquals(0x8082, entry.address());
        assertNull(entry.condition());
    }

    @Test
    void anEntryTakesAConditionAfterIf() {
        var entry = Addresses.parseEntry("$8082 if [$0770] == 1");

        assertEquals(0x8082, entry.address());
        assertEquals("[$0770] == $01", entry.condition().text());
    }

    @Test
    void anEntryWithSomethingOtherThanIfAfterTheAddressIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Addresses.parseEntry("$8082 when a == 1"));
        assertThrows(IllegalArgumentException.class, () -> Addresses.parseEntry("$8082 if"));
    }
}
