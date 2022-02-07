package com.github.dimiro1.mynes.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of AddressSpace that supports memory mapping.
 */
public class MappedMemory implements Memory {
    private final List<MemoryMapping> ranges = new ArrayList<>();

    /**
     * Map the given memory to the given address range.
     *
     * @param base   the base address
     * @param length the length in bytes
     * @param memory the mapped memory
     */
    public void map(final int base, final int length, final Memory memory) {
        this.ranges.add(new MemoryMapping(base, length, memory));
    }

    /**
     * Read a single byte from one of the mapped memories.
     * Important: If more than one memory can is assigned to the same memory space the first one will be used.
     *
     * @param address the memory address to read.
     * @throws UnmappedAddress if the address is unmapped.
     */
    @Override
    public int read(final int address) {
        return this.getFirstRange(address).read(address);
    }

    /**
     * Writes a single byte into one of the mapped memories.
     * Important: Must write to all ranges that accepts the address.
     *
     * @param address The memory address to write the value.
     * @param data    The data to write.
     * @throws UnmappedAddress if the address is unmapped.
     */
    @Override
    public void write(final int address, final int data) {
        this.getRanges(address).forEach(m -> m.write(address, data));
    }

    /**
     * Returns the sum of all mapped memories.
     */
    @Override
    public int getLength() {
        return this.ranges.stream()
                .reduce(0, (acc, mem) -> acc + mem.memory().getLength(), Integer::sum);
    }

    private List<Memory> getRanges(final int address) {
        return ranges.stream()
                .filter(mapping -> mapping.accepts(address))
                .map(MemoryMapping::memory)
                .collect(Collectors.toList());
    }

    private Memory getFirstRange(final int address) {
        return ranges.stream()
                .filter(mapping -> mapping.accepts(address))
                .findFirst()
                .orElseThrow(() -> new UnmappedAddress(address))
                .memory();
    }

    private record MemoryMapping(int base, int length, Memory memory) {
        /**
         * Returns true if the address space is able to handle the given address.
         *
         * @param address the memory address to check.
         */
        boolean accepts(int address) {
            return address >= base && address < (base + length);
        }
    }
}
