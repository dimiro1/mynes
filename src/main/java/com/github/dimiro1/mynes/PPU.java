package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;

public class PPU {
    private final BUS bus;
    private final Mapper mapper;
    private final VRAM vram;
    private int ppuCtrl;
    private int ppuMask;
    private int ppuStatus;
    private int oamAddress;
    private int oamData;
    private int ppuScroll;
    private int ppuAddress;
    private int ppuData;
    private int oamDMA;

    public PPU(final BUS bus, final Mapper mapper) {
        this.bus = bus;
        this.mapper = mapper;
        this.vram = new VRAM();
    }

    public void tick() {
    }

    public int read(final int address) {
        return switch (address) {
            case 0x0 -> ByteUtils.ensureByte(ppuCtrl);
            case 0x1 -> ByteUtils.ensureByte(ppuMask);
            case 0x2 -> ByteUtils.ensureByte(ppuStatus);
            case 0x3 -> ByteUtils.ensureByte(oamAddress);
            case 0x4 -> ByteUtils.ensureByte(oamData);
            case 0x5 -> ByteUtils.ensureByte(ppuScroll);
            case 0x6 -> ByteUtils.ensureByte(ppuAddress);
            case 0x7 -> ByteUtils.ensureByte(oamDMA);
            default -> throw new IllegalStateException("Unexpected address: " + address);
        };
    }

    public void write(final int address, final int data) {
        switch (address) {
            case 0x0 -> ppuCtrl = ByteUtils.ensureByte(data);
            case 0x1 -> ppuMask = ByteUtils.ensureByte(data);
            case 0x2 -> ppuStatus = ByteUtils.ensureByte(data);
            case 0x3 -> oamAddress = ByteUtils.ensureByte(data);
            case 0x4 -> oamData = ByteUtils.ensureByte(data);
            case 0x5 -> ppuScroll = ByteUtils.ensureByte(data);
            case 0x6 -> ppuAddress = ByteUtils.ensureByte(data);
            case 0x7 -> oamDMA = ByteUtils.ensureByte(data);
        }
    }
}
