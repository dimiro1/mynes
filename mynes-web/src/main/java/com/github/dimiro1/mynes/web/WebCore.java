package com.github.dimiro1.mynes.web;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.Palettes;
import org.teavm.jso.JSExport;

/** The deliberately small boundary between the browser and the unchanged emulator core. */
public final class WebCore {
    private static NES nes;
    private static final int[] COLOURS = Palettes.NESDEV.colours();
    private static final int[] PIXELS = new int[256 * 240];

    private WebCore() {
    }

    public static void main(String[] args) {
        // TeaVM's entry point. The browser calls the exported functions below.
    }

    @JSExport
    public static String load(byte[] rom, String filename, String sha256) {
        // Construct the replacement before publishing it so a failed load leaves the old game usable.
        var next = new NES(Cart.load(rom, filename, sha256));
        nes = next;
        return next.getRegion().label();
    }

    @JSExport
    public static void reset() {
        if (nes != null) {
            nes.reset();
        }
    }

    @JSExport
    public static int[] frame(int buttons) {
        if (nes == null) {
            return PIXELS;
        }

        nes.getController1().setButtons(buttons);
        var ppu = nes.getPPU();
        var completed = ppu.getFrame();
        do {
            nes.tick();
        } while (ppu.getFrame() == completed);

        var indices = ppu.getFrameBuffer();
        for (var i = 0; i < PIXELS.length; i++) {
            PIXELS[i] = COLOURS[indices[i]];
        }
        return PIXELS;
    }

    @JSExport
    public static short[] audio() {
        if (nes == null) {
            return new short[0];
        }
        var apu = nes.getAPU();
        var result = new short[apu.availableSamples()];
        apu.drainSamples(result);
        return result;
    }
}
