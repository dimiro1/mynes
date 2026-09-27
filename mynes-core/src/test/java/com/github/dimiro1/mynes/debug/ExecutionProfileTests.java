package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionProfileTests {
    @Test
    void countsExecutedPrgLocationsAndCpuRunCycles() {
        var nes = new NES(Cart.load(SplitROM.image(), "profile.nes"));
        var profile = new ExecutionProfile(nes);
        nes.getCPU().addEventListener(profile);

        for (var i = 0; i < 100; i++) nes.step();

        var measured = profile.snapshot();
        assertTrue(measured.instructions() > 0);
        assertTrue(measured.cycles() > 0);
        assertTrue(measured.entries().stream().anyMatch(entry -> entry.prgOffset() == 0));

        profile.reset();
        assertEquals(0, profile.snapshot().instructions());
        assertEquals(0, profile.snapshot().cycles());
    }
}
