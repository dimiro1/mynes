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

    @Test
    void attributesSubroutineCyclesToTheCallingPathAndKeepsFlatTotalsInSync() {
        var nes = new NES(Cart.load(callROM(), "calls.nes"));
        var profile = new ExecutionProfile(nes);
        nes.getCPU().addEventListener(profile);

        for (var i = 0; i < 30; i++) nes.step();

        var measured = profile.snapshot();
        assertEquals(measured.instructions(), measured.flame().instructions());
        assertEquals(measured.cycles(), measured.flame().cycles());
        var call = measured.flame().children().stream()
                .filter(node -> node.frame().kind() == ExecutionProfile.FrameKind.CALL)
                .findFirst().orElseThrow();
        assertEquals(6, call.frame().prgOffset());
        assertTrue(call.cycles() > 0);
        assertTrue(call.children().stream().anyMatch(node ->
                node.frame().kind() == ExecutionProfile.FrameKind.LOCATION
                        && node.frame().prgOffset() == 6));
        assertTrue(measured.flame().children().stream().anyMatch(node ->
                node.frame().kind() == ExecutionProfile.FrameKind.LOCATION
                        && node.frame().prgOffset() == 3),
                "RTS returns to the caller, so the following JMP belongs to the root");

        profile.reset();
        for (var i = 0; i < 8; i++) nes.step();
        var restarted = profile.snapshot();
        assertTrue(restarted.instructions() > 0);
        assertEquals(restarted.cycles(), restarted.flame().cycles());
    }

    @Test
    void recordsHardwareNmiUnderTheInterruptedCallAndReturnsToIt() {
        var nes = new NES(Cart.load(callROM(), "interrupt.nes"));
        var profile = new ExecutionProfile(nes);
        nes.getCPU().addEventListener(profile);
        for (var i = 0; i < 20 && nes.getCPU().getState().pc() != 0x8003; i++) nes.step();
        assertEquals(0x8003, nes.getCPU().getState().pc());

        nes.getCPU().requestNMI();
        for (var i = 0; i < 10; i++) nes.step();

        var measured = profile.snapshot();
        assertEquals(measured.cycles(), measured.flame().cycles());
        assertTrue(hasFrame(measured.flame(), ExecutionProfile.FrameKind.NMI, 0x10));
        assertTrue(measured.flame().children().stream().anyMatch(node ->
                node.frame().kind() == ExecutionProfile.FrameKind.NMI),
                "an interrupt after RTS belongs to the caller, not the returned subroutine");
        assertTrue(measured.entries().stream().anyMatch(entry -> entry.prgOffset() == 0x10));
    }

    @Test
    void stoppingRecordingDoesNotChargeLaterExecutionToTheLastLocation() {
        var nes = new NES(Cart.load(callROM(), "pause.nes"));
        var profile = new ExecutionProfile(nes);
        nes.getCPU().addEventListener(profile);
        for (var i = 0; i < 8; i++) nes.step();

        nes.getCPU().removeEventListener(profile);
        profile.pause();
        var stopped = profile.snapshot();
        for (var i = 0; i < 20; i++) nes.step();

        assertEquals(stopped.cycles(), profile.snapshot().cycles());
        assertEquals(stopped.flame(), profile.snapshot().flame());
    }

    private static boolean hasFrame(final ExecutionProfile.FlameNode node,
                                    final ExecutionProfile.FrameKind kind, final int offset) {
        return node.frame().kind() == kind && node.frame().prgOffset() == offset
                || node.children().stream().anyMatch(child -> hasFrame(child, kind, offset));
    }

    private static byte[] callROM() {
        var image = new byte[16 + 0x4000 + 0x2000];
        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;
        // $8000: JSR $8006; $8003: JMP $8000; $8006: NOP; RTS.
        var program = new int[]{0x20, 0x06, 0x80, 0x4C, 0x00, 0x80, 0xEA, 0x60};
        for (var i = 0; i < program.length; i++) image[16 + i] = (byte) program[i];
        image[16 + 0x10] = (byte) 0xEA;
        image[16 + 0x11] = (byte) 0x40;
        image[16 + 0x3FFA] = 0x10;
        image[16 + 0x3FFB] = (byte) 0x80;
        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;
        return image;
    }
}
