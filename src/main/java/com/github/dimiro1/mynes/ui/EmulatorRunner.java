package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.NES;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs a {@link NES} on its own thread, one frame at a time, and hands the finished frames to a
 * {@link ScreenComponent} -- all of them at normal speed, sixty a second of them when fast
 * forwarding -- and the sound that went with them to an {@link AudioOutput}.
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

    /**
     * How many samples the buffer between the APU and the sound card holds.
     * <p>
     * A frame is about 735 of them, and the drain happens once a frame, so this is several
     * frames' worth of slack for a frame that ran long. Anything past it stays in the APU's own
     * ring until the next time round.
     */
    private static final int AUDIO_BUFFER_SAMPLES = 4096;

    private final NES nes;
    private final ScreenComponent screen;
    private final AudioOutput audio = new AudioOutput();

    /**
     * How long one frame of this machine lasts, which is the whole of what the region means to this
     * class: 16.6ms on NTSC and 20ms on PAL. Everything else about the difference is inside the NES
     * and this loop cannot tell.
     */
    private final long frameNanos;

    /**
     * Where the APU's finished samples land on their way to the sound card. Belongs to the
     * emulation thread, like everything else it is handed to.
     */
    private final short[] samples = new short[AUDIO_BUFFER_SAMPLES];

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
        this.frameNanos = nes.getRegion().frameNanos();
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
     * Blocks for at most the rest of the current frame, so around 17ms in the usual case, which is
     * short enough to do from the EDT. The interrupt is what makes that true: it cuts the wait the
     * thread is likely to be sitting in.
     * <p>
     * The one wait it cannot cut is a full-buffer write to the sound card, which is not
     * interruptible and can be another 67 milliseconds on top. Still under a tenth of a second,
     * and only on the frame a machine happens to be torn down on.
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
     * The same, for something that replaces the machine's state wholesale rather than nudging it.
     * <p>
     * The difference is the sound card, which is holding up to a tenth of a second of a game that,
     * by the time it plays, will be a game the player is no longer in. Dropped rather than played
     * out, for the reason a pause drops it: what comes out of the speaker should be what is on the
     * screen.
     */
    public void postStateChange(final Runnable command) {
        commands.add(() -> {
            command.run();
            audio.flush();
        });
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

    /**
     * Silences the sound, or lets it be heard again. Takes effect within a frame.
     * <p>
     * The machine is not told: a muted APU still runs, still raises its interrupts and still
     * paces the loop, because a game that sounded different depending on the volume would be a
     * different game.
     */
    public void setMuted(final boolean muted) {
        post(() -> audio.setMuted(muted));
    }

    private void run() {
        logger.info("emulation started");

        var ppu = nes.getPPU();
        var apu = nes.getAPU();

        try {
            audio.open();

            var speed = this.speed;
            var deadline = System.nanoTime();
            var nextPresent = deadline + frameNanos;
            var lastFrame = ppu.getFrame();
            var wasPaused = false;

            while (running) {
                runPendingCommands();

                // Normally a no-op -- nothing has been clocked since this was last assigned. It
                // matters when a command has just loaded a save state, which can move the frame
                // counter backwards: the loop below waits for the counter to *change*, so a stale
                // value here would satisfy it after a single tick and present a torn frame.
                lastFrame = ppu.getFrame();

                if (paused) {
                    if (!wasPaused) {
                        // What the card is still holding is up to a tenth of a second of a game
                        // that has stopped. Dropped rather than played out, so that the sound
                        // stops when the picture does.
                        audio.flush();
                        wasPaused = true;
                    }

                    // A frame's worth of sleep at a time, so a resume or a posted command is
                    // picked up quickly, and the schedule restarts cleanly on resume instead of
                    // sprinting through the pause as missed frames.
                    LockSupport.parkNanos(frameNanos);
                    deadline = System.nanoTime();
                    continue;
                }

                wasPaused = false;

                if (this.speed != speed) {
                    // Both schedules start again from here rather than carrying a deadline written
                    // in the old speed's units -- which, coming off unlimited, is not a deadline
                    // that was being kept at all.
                    speed = this.speed;
                    deadline = System.nanoTime();
                    nextPresent = deadline + frameNanos;
                }

                // The PPU has no frame-complete callback; its frame counter is the signal. One
                // tick is three dots, so this can overshoot the boundary by up to two of them --
                // at most the first pixel of the next frame arrives early, on scanline 0, which
                // the overscan crop hides anyway.
                do {
                    nes.tick();
                } while (ppu.getFrame() == lastFrame);

                lastFrame = ppu.getFrame();

                // A frame's worth of sound, handed over before the picture is: at normal speed
                // this blocks until the card has room, which is the other half of the pacing
                // below, and there is no sense making the audio wait on a frame that is only
                // going to be dropped anyway. Fast forwarding cannot block -- there is no way to
                // hand a sound card audio faster than real time -- so what does not fit is lost,
                // and fast forward sounds chopped rather than sped up.
                audio.write(samples, apu.drainSamples(samples), speed == EmulationSpeed.NORMAL);

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

                    nextPresent += frameNanos;
                    if (nextPresent - now < 0) {
                        // A frame's worth behind, which is a machine too slow for the speed it was
                        // asked for. Owing it pictures it will never draw helps nobody.
                        nextPresent = now + frameNanos;
                    }
                }

                if (speed == EmulationSpeed.UNLIMITED) {
                    // Nothing to wait for. The host's speed is the only limit there is.
                    continue;
                }

                // Absolute deadlines rather than "sleep 16ms": the time spent emulating the frame
                // comes out of the wait instead of being added to it, so the error cannot pile up.
                deadline += speed.frameNanos(nes.getRegion());

                // Read again: presenting the frame took real time too, and at eight times speed
                // the whole budget is two milliseconds.
                now = System.nanoTime();
                if (deadline - now > 0) {
                    LockSupport.parkNanos(deadline - now);
                } else if (now - deadline > MAX_LAG_FRAMES * speed.frameNanos(nes.getRegion())) {
                    deadline = now;
                }
            }
        } catch (Throwable t) {
            logger.error("emulation failed at frame {}", ppu.getFrame(), t);
        } finally {
            audio.close();
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
