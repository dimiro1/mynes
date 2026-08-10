package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.NES;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs a {@link NES} on its own thread, one frame at a time, and hands the finished frames to a
 * {@link ScreenComponent} -- all of them at normal speed, sixty a second of them when fast
 * forwarding.
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
     * How far behind schedule the loop tolerates before it gives up on catching up, counted in
     * frames at whatever speed it is running.
     * <p>
     * Without this, a long garbage collection or a suspended laptop leaves the deadline in the
     * past and the loop sprints through every frame it owes at full speed. Past this much debt the
     * frames are simply dropped.
     */
    private static final int MAX_LAG_FRAMES = 5;

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

    /**
     * How fast to run. Written from the event dispatch thread and read here, so the loop picks a
     * change up at the next frame boundary rather than mid-frame.
     */
    private volatile EmulationSpeed speed = EmulationSpeed.NORMAL;

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

    /**
     * Runs the machine at {@code speed} from here on. Takes effect within a frame.
     * <p>
     * Only the wait between frames changes: the machine itself is clocked exactly as it is at
     * normal speed, since nothing inside it knows what a second is. What does change is what
     * reaches the screen -- see {@link #run()} -- because at speed the frames come faster than any
     * display can show them.
     */
    public void setSpeed(final EmulationSpeed speed) {
        this.speed = speed;
    }

    public EmulationSpeed getSpeed() {
        return speed;
    }

    private void run() {
        logger.info("emulation started");

        var ppu = nes.getPPU();

        try {
            var speed = this.speed;
            var deadline = System.nanoTime();
            var nextPresent = deadline + EmulationSpeed.FRAME_NANOS;
            var lastFrame = ppu.getFrame();

            while (running) {
                runPendingCommands();

                if (paused) {
                    // A frame's worth of sleep at a time, so a resume or a posted command is
                    // picked up quickly, and the schedule restarts cleanly on resume instead of
                    // sprinting through the pause as missed frames.
                    LockSupport.parkNanos(EmulationSpeed.FRAME_NANOS);
                    deadline = System.nanoTime();
                    continue;
                }

                if (this.speed != speed) {
                    // Both schedules start again from here rather than carrying a deadline written
                    // in the old speed's units -- which, coming off unlimited, is not a deadline
                    // that was being kept at all.
                    speed = this.speed;
                    deadline = System.nanoTime();
                    nextPresent = deadline + EmulationSpeed.FRAME_NANOS;
                }

                // The PPU has no frame-complete callback; its frame counter is the signal. One
                // tick is three dots, so this can overshoot the boundary by up to two of them --
                // at most the first pixel of the next frame arrives early, on scanline 0, which
                // the overscan crop hides anyway.
                do {
                    nes.tick();
                } while (ppu.getFrame() == lastFrame);

                lastFrame = ppu.getFrame();

                // Fast forward finishes frames faster than any display can show them, so most of
                // them are dropped rather than handed over. A frame nobody will see still costs a
                // quarter of a megabyte copied and 61440 palette lookups on this thread, under a
                // lock the event dispatch thread wants for painting, and the picture is no better
                // for it: what the eye gets either way is sixty frames a second, further apart in
                // the machine's time.
                //
                // Absolute again, and for a sharper reason than the frame deadline. Timing each
                // one from when the last actually went out adds that frame's overshoot to the
                // interval, and at two times speed -- where the picture wants every second frame
                // and the overshoot is what decides which -- the drift costs a quarter of them.
                var now = System.nanoTime();
                if (speed == EmulationSpeed.NORMAL || now - nextPresent >= 0) {
                    screen.present(ppu.getFrameBuffer());

                    nextPresent += EmulationSpeed.FRAME_NANOS;
                    if (nextPresent - now < 0) {
                        // A frame's worth behind, which is a machine too slow for the speed it was
                        // asked for. Owing it pictures it will never draw helps nobody.
                        nextPresent = now + EmulationSpeed.FRAME_NANOS;
                    }
                }

                if (speed == EmulationSpeed.UNLIMITED) {
                    // Nothing to wait for. The host's speed is the only limit there is.
                    continue;
                }

                // Absolute deadlines rather than "sleep 16ms": the time spent emulating the frame
                // comes out of the wait instead of being added to it, so the error cannot pile up.
                deadline += speed.frameNanos();

                // Read again: presenting the frame took real time too, and at eight times speed
                // the whole budget is two milliseconds.
                now = System.nanoTime();
                if (deadline - now > 0) {
                    LockSupport.parkNanos(deadline - now);
                } else if (now - deadline > MAX_LAG_FRAMES * speed.frameNanos()) {
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
