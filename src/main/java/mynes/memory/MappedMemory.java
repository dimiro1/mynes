package mynes.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of AddressSpace that supports memory mapping.
 */
public class MappedMemory implements Memory {
    private final List<MemoryRelocatable> ranges = new ArrayList<>();

    /**
     * Map the given memory to the given address range.
     *
     * @param from   the from address
     * @param to     the end  address
     * @param memory the mapped memory
     */
    public void map(final int from, final int to, final Memory memory) {
        this.ranges.add(new MemoryRelocatable(from, to, memory));
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

    @Override
    public String getName() {
        return "mapped";
    }

    private List<Memory> getRanges(final int address) {
        return ranges.stream()
                .filter(mapping -> mapping.accepts(address))
                .collect(Collectors.toList());
    }

    private Memory getFirstRange(final int address) {
        return ranges.stream()
                .filter(mapping -> mapping.accepts(address))
                .findFirst()
                .orElseThrow(() -> new UnmappedAddress(address));
    }

    private record MemoryRelocatable(int from, int to, Memory memory) implements Memory {
        boolean accepts(final int address) {
            return address >= from && address <= to;
        }

        @Override
        public int read(final int address) {
            return this.memory.read(this.translate(address));
        }

        @Override
        public void write(final int address, final int data) {
            this.memory.write(this.translate(address), data);
        }

        @Override
        public int getLength() {
            return this.memory.getLength();
        }

        @Override
        public String getName() {
            return String.format("relocatable:%s", this.memory.getName());
        }

        private int translate(final int address) {
            return address - this.from;
        }
    }
}
