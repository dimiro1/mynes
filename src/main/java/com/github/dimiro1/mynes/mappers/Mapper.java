package com.github.dimiro1.mynes.mappers;

/**
 * The cartridge, as the rest of the console sees it.
 * <p>
 * Addresses arrive here exactly as they sit on the CPU or PPU bus, not folded down to an offset
 * into anything: $8000-$FFFF for {@link #prgRead}, $6000-$7FFF for {@link #prgRAMRead},
 * $0000-$1FFF for {@link #charRead}. Mappers decode their registers out of the address lines --
 * MMC1 uses bits 13 and 14, MMC3 uses {@code address & 0xE001} -- and NESdev documents all of
 * that in CPU addresses, so passing anything else would mean translating every wiki page.
 */
public interface Mapper {

    /**
     * Reads a single byte of PRG ROM.
     *
     * @param address an address in $8000-$FFFF.
     */
    int prgRead(int address);

    /**
     * Writes to $8000-$FFFF.
     * <p>
     * There is no RAM there to write to: on almost every board this is how the mapper's registers
     * are reached, and a mapper without registers simply ignores it.
     *
     * @param address an address in $8000-$FFFF.
     */
    void prgWrite(int address, int data);

    /**
     * Reads a single byte from the cartridge RAM at $6000-$7FFF.
     * <p>
     * Most boards carry 8KB there, battery backed or not. One with nothing fitted leaves the bus
     * floating, modelled here as zero -- which is what this window read back as before any mapper
     * owned it.
     *
     * @param address an address in $6000-$7FFF.
     */
    default int prgRAMRead(int address) {
        return 0;
    }

    /**
     * Writes a single byte into the cartridge RAM at $6000-$7FFF, if there is any and the mapper
     * has it enabled.
     *
     * @param address an address in $6000-$7FFF.
     */
    default void prgRAMWrite(int address, int data) { /* No RAM on the board by default */ }

    /**
     * Reads a single byte from the CHAR ROM/RAM.
     *
     * @param address an address in $0000-$1FFF.
     */
    int charRead(int address);

    /**
     * Writes a single byte into the given address on CHAR ROM/RAM.
     *
     * @param address an address in $0000-$1FFF.
     */
    void charWrite(int address, int data);

    /**
     * Tells the mapper what the PPU has just put on its address bus.
     * <p>
     * The cartridge is wired to all fourteen PPU address lines, not only the ones that select
     * pattern tables, so it sees nametable and palette accesses go past as well. MMC3 counts
     * scanlines by watching A12 rise, which is why this is here at all: nothing else needs it.
     *
     * @param address the address on the bus, already masked to $0000-$3FFF.
     */
    default void ppuAddress(int address) { /* Nothing on the board is watching by default */ }

    /**
     * One PPU dot has passed.
     * <p>
     * The time base for a mapper that has to tell a real signal from a glitch on the address bus:
     * {@link #ppuAddress} says what the bus is doing, this says how long it has been doing it.
     */
    default void ppuTick() { /* Nothing on the board is counting by default */ }

    /**
     * Hands the mapper the /IRQ line.
     * <p>
     * Called once the console is built, rather than passed to the constructor, because the
     * cartridge exists before the CPU that would be interrupted does. A mapper without interrupt
     * hardware ignores this.
     */
    default void setIRQHandler(IRQHandler handler) { /* No interrupt hardware by default */ }

    /**
     * How this cartridge wires the console's nametable RAM.
     * <p>
     * Asked afresh on every nametable access rather than cached, because a mapper with mirroring
     * registers can change the answer between one access and the next.
     */
    Mirroring mirroring();
}
