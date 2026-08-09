package com.github.dimiro1.mynes;

/**
 * The slice of the system bus that the PPU depends on.
 * <p>
 * {@link BUS} is the real implementation; the seam exists so the PPU can be driven without a CPU
 * behind it, and so a test can watch the /NMI line directly instead of inferring it from CPU
 * behaviour. It mirrors the {@link CPUBus} precedent.
 */
public interface PPUBus {
    /**
     * Drives the /NMI line the PPU shares with the CPU.
     * <p>
     * The line is a level, not a pulse: the PPU holds it asserted for as long as both the VBlank
     * flag and the NMI enable bit are set, and the CPU latches the transition into asserted. The
     * PPU calls this whenever either of those two inputs changes, so a game that enables NMI
     * halfway through VBlank gets its interrupt immediately, and one that reads $2002 on the
     * exact dot the flag is set never sees an edge at all.
     *
     * @param level true while the line is asserted (pulled low on real hardware).
     */
    void setNMILine(boolean level);
}
