package com.github.dimiro1.mynes;

/**
 * Told about every byte the CPU writes, which is what a watchpoint is built out of.
 * <p>
 * The one hole worth knowing about: this sees writes the <em>CPU</em> makes, because {@link MMU}'s
 * write is the only way one gets onto the bus. A sprite DMA does not pass through it -- it copies
 * into OAM by calling the PPU directly, 256 times, from inside {@link MMU#tickDMA} -- and neither do
 * the PPU's own writes into video memory. A watch on $2004 will not fire during a DMA. Closing that
 * would mean a hook inside a five hundred cycle transfer loop, which is a high price for a question
 * nobody has asked yet.
 *
 * @see CPUEventListener
 */
@FunctionalInterface
public interface MemoryWriteListener {
    /**
     * Called before the byte lands.
     *
     * @param address where it is going, $0000-$FFFF.
     * @param value   the byte. Handed over rather than left to be read back, because at this point
     *                the address still holds the one it is about to replace -- and on half the map
     *                reading it at all would be a side effect.
     */
    void onWrite(int address, int value);
}
