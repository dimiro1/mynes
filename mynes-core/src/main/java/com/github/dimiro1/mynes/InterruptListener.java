package com.github.dimiro1.mynes;

/**
 * Told whenever the processor commits to an interrupt, and to which one.
 * <p>
 * The third of these seams, beside {@link MemoryReadListener} and {@link MemoryWriteListener}, and
 * the one that is not about the bus at all. It exists because <em>when in the frame</em> an
 * interrupt arrived is a fact nothing else in the machine records: an MMC3 scanline counter firing
 * three lines late, an NMI a game switched off half way down the screen, and a DMC interrupt
 * landing in the middle of a music driver are all invisible in memory afterwards, and all obvious
 * the moment they are marked against the beam.
 * <p>
 * <b>Called at the cycle the vector is chosen</b> rather than when the line was asserted, which is
 * the honest moment: an interrupt asserted while the I flag was set is not one the processor
 * served, and an NMI that arrived mid-sequence hijacks whatever was already pushing. By this cycle
 * both of those have been settled.
 * <p>
 * <b>A BRK is not one of these.</b> It picks its vector through the same code and is deliberately
 * not reported: it is an instruction the program ran rather than a device interrupting it, and a
 * breakpoint or a trace is where to see one. The blind spot that leaves is a BRK an NMI hijacked
 * mid-sequence, which really is an NMI being serviced and still does not arrive here -- which is
 * worth knowing rather than worth closing, since a program running BRK at all is one a breakpoint
 * suits better.
 *
 * @see CPU#setInterruptListener
 */
@FunctionalInterface
public interface InterruptListener {
    /**
     * Called on the cycle the vector is picked, with the return address already on the stack.
     *
     * @param nmi whether this is the non-maskable one. False is the IRQ line, whichever device is
     *            holding it -- the frame counter, the DMC, or a cartridge that counts scanlines.
     * @param pc  where the processor will come back to, which is the code that was interrupted.
     */
    void onInterrupt(boolean nmi, int pc);
}
