package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.NES;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs a {@link NES} on its own thread, one frame at a time, and hands each finished frame to a
 * {@link ScreenComponent}.
 * <p>
 * Emulation cannot happen on the event dispatch thread: a machine that never stops running would
 * never let the EDT paint a menu. So this is the only thread that touches the NES, and the only
 * thing it touches on the UI side is {@link ScreenComponent#present(int[])}, which is written to
 * be called from here. {@link #start()} and {@link #stop()} are for the EDT, and anything else
 * the UI wants done to the machine -- a reset, a debug switch -- goes through {@link #post} and
 * runs here, between frames.
 * <p>
 * The one deliberate exception is the CHR viewer, which reads the mapper's character memory and
 * the PPU's palette RAM from the EDT while this thread runs. It is a debug window watching memory
 * whose reads cannot tear; a stale tile in it would not be worth a lock on every pattern fetch.
 *
 * @see com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame
 */
public class EmulatorRunner {
    private static final Logger logger = LoggerFactory.getLogger("EMU");

    /**
     * One NTSC frame. The 2C02 draws 60.0988 frames a second rather than 60, which is a third of
     * a percent -- inaudible now, but it is the number the APU will have to agree with later.
     */
    private static final long FRAME_NANOS = 16_639_267L;

    /**
     * How far behind schedule the loop tolerates before it gives up on catching up.
     * <p>
     * Without this, a long garbage collection or a suspended laptop leaves the deadline in the
     * past and the loop sprints through every frame it owes at full speed. Past this much debt the
     * frames are simply dropped.
     */
    private static final long MAX_LAG_NANOS = 5 * FRAME_NANOS;

    private final NES nes;
    private final ScreenComponent screen;

    /**
     * Work the UI has asked to have done to the machine. Drained on the emulation thread at frame
     * boundaries, which is what makes a menu action safe without putting a lock on the machine:
     * the queue's own synchronisation carries the handoff.
     */
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private volatile boolean paused;
    private Thread thread;

    public EmulatorRunner(final NES nes, final ScreenComponent screen) {
        this.nes = nes;
        this.screen = screen;
    }

    /**
     * Starts the emulation thread. Call from the event dispatch thread.
     */
    public void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }

        running = true;
        thread = Thread.ofPlatform().name("emulation").daemon(true).start(this::run);
    }

    /**
     * Stops the emulation thread and waits for it to finish. Call from the event dispatch thread.
     * <p>
     * Blocks for at most the rest of the current frame, so around 17ms in the worst case, which is
     * short enough to do from the EDT. The interrupt is what makes that true: it cuts the wait the
     * thread is likely to be sitting in.
     */
    public void stop() {
        if (thread == null) {
            return;
        }

        running = false;
        thread.interrupt();

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        thread = null;
    }

    /**
     * Hands an action to the emulation thread, which runs it between frames -- so within about
     * 17ms, paused or not. This is how the UI touches the machine: nothing here blocks, and
     * nothing on the EDT ever handles the NES itself.
     */
    public void post(final Runnable command) {
        commands.add(command);
    }

    /**
     * Freezes the machine, or lets it run again. Takes effect within a frame. While paused the
     * last finished frame stays on screen and posted commands still run.
     */
    public void setPaused(final boolean paused) {
        this.paused = paused;
    }

    public boolean isPaused() {
        return paused;
    }

    private void run() {
        logger.info("emulation started");

        var ppu = nes.getPPU();

        try {
            var deadline = System.nanoTime();
            var lastFrame = ppu.getFrame();

            while (running) {
                runPendingCommands();

                if (paused) {
                    // A frame's worth of sleep at a time, so a resume or a posted command is
                    // picked up quickly, and the schedule restarts cleanly on resume instead of
                    // sprinting through the pause as missed frames.
                    LockSupport.parkNanos(FRAME_NANOS);
                    deadline = System.nanoTime();
                    continue;
                }

                // The PPU has no frame-complete callback; its frame counter is the signal. One
                // tick is three dots, so this can overshoot the boundary by up to two of them --
                // at most the first pixel of the next frame arrives early, on scanline 0, which
                // the overscan crop hides anyway.
                do {
                    nes.tick();
                } while (ppu.getFrame() == lastFrame);

                lastFrame = ppu.getFrame();
                screen.present(ppu.getFrameBuffer());

                // Absolute deadlines rather than "sleep 16ms": the time spent emulating the frame
                // comes out of the wait instead of being added to it, so the error cannot pile up.
                deadline += FRAME_NANOS;

                var now = System.nanoTime();
                if (deadline - now > 0) {
                    LockSupport.parkNanos(deadline - now);
                } else if (now - deadline > MAX_LAG_NANOS) {
                    deadline = now;
                }
            }
        } catch (Throwable t) {
            logger.error("emulation failed at frame {}", ppu.getFrame(), t);
        }

        logger.info("emulation stopped");
    }

    private void runPendingCommands() {
        Runnable command;
        while ((command = commands.poll()) != null) {
            command.run();
        }
    }
}
