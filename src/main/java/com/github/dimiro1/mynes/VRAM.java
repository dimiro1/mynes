package com.github.dimiro1.mynes;

public class VRAM {
    private final int[] patternTable0 = new int[0x1000];
    private final int[] patternTable1 = new int[0x1000];
    private final int[] nameTable0 = new int[0x0400];
    private final int[] nameTable1 = new int[0x0400];
    private final int[] nameTable2 = new int[0x0400];
    private final int[] nameTable3 = new int[0x0400];
    private final int[] palettes = new int[0x0020];

    public int read(final int address) {
        return switch (address & 0xF000) {
            case 0x0000 -> patternTable0[address];
            case 0x1000 -> patternTable1[address - 0x1000];
            case 0x2000 -> switch (address & 0x0F00) {
                case 0x000, 0x100, 0x200, 0x300 -> nameTable0[address - 0x2000];
                case 0x400, 0x500, 0x600, 0x700 -> nameTable1[address - 0x2000];
                case 0x800, 0x900, 0xA00, 0xB00 -> nameTable2[address - 0x2000];
                case 0xC00, 0xD00, 0xE00, 0xF00 -> nameTable3[address - 0x2000];
                default -> 0xFF;
            };
            case 0x3000 -> switch (address & 0x0F00) {
                case 0x000, 0x100, 0x200, 0x300 -> nameTable0[address - 0x3000];
                case 0x400, 0x500, 0x600, 0x700 -> nameTable1[address - 0x3000];
                case 0x800, 0x900, 0xA00, 0xB00 -> nameTable2[address - 0x3000];
                case 0xC00, 0xD00, 0xE00 -> nameTable3[address - 0x3000];
                case 0xF00 -> palettes[(address - 0x3000) % 0x20];
                default -> 0xFF;
            };
            default -> 0xFF;
        };
    }

    public void write(final int address, final int data) {
        switch (address & 0xF000) {
            case 0x0000 -> patternTable0[address] = data;
            case 0x1000 -> patternTable1[address - 0x1000] = data;
            case 0x2000 -> {
                switch (address & 0x0F00) {
                    case 0x000, 0x100, 0x200, 0x300 -> nameTable0[address - 0x2000] = data;
                    case 0x400, 0x500, 0x600, 0x700 -> nameTable1[address - 0x2000] = data;
                    case 0x800, 0x900, 0xA00, 0xB00 -> nameTable2[address - 0x2000] = data;
                    case 0xC00, 0xD00, 0xE00, 0xF00 -> nameTable3[address - 0x2000] = data;
                }
            }
            case 0x3000 -> {
                switch (address & 0x0F00) {
                    case 0x000, 0x100, 0x200, 0x300 -> nameTable0[address - 0x3000] = data;
                    case 0x400, 0x500, 0x600, 0x700 -> nameTable1[address - 0x3000] = data;
                    case 0x800, 0x900, 0xA00, 0xB00 -> nameTable2[address - 0x3000] = data;
                    case 0xC00, 0xD00, 0xE00 -> nameTable3[address - 0x3000] = data;
                    case 0xF00 -> palettes[(address - 0x3000) % 0x20] = data;
                }
            }
        }
    }
}
