package com.github.dimiro1.mynes;

public class NES {
    private final BUS bus;

    public NES(final Cart cart) {
        Controller controller1 = new StandardController();
        Controller controller2 = new StandardController();
        bus = new BUS(cart.mapper(), controller1, controller2);
        bus.initialize();
    }

    public CPU getCPU() {
        return bus.getCPU();
    }

    public PPU getPPU() {
        return bus.getPPU();
    }

    public MMU getMemory() {
        return bus.getMMU();
    }

    public Controller getController1() {
        return bus.getController1();
    }

    public Controller getController2() {
        return bus.getController2();
    }

    public BUS getBus() {
        return bus;
    }

    public void tick() {
        bus.getCPU().tick();
        bus.getPPU().tick();
        bus.getPPU().tick();
    }

    public void step() {
        bus.getCPU().step();
    }
}
