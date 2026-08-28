package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.state.Movie;
import com.github.dimiro1.mynes.state.MovieException;
import com.github.dimiro1.mynes.state.MovieRecorder;
import com.github.dimiro1.mynes.state.Rewind;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntSupplier;

/**
 * Runs a {@link NES} on its own thread, one frame at a time, and hands the finished frames to a
 * {@link ScreenComponent} -- all of them at normal speed, sixty a second of them when fast
 * forwarding -- and the sound that went with them to an {@link AudioOutput}.
 * <p>
 * It also runs the machine the other way. Every other finished frame is written into a
 * {@link Rewind} ring, and while the rewind key is held the loop pops one of them per display tick
 * instead of clocking anything -- so the game goes backwards at twice speed without a single
 * instruction being re-executed, because the picture travels inside the state. Fast Forward applies
 * to that wait like any other, which makes holding both keys a faster reverse still.
 * <p>
 * And it writes sessions down, and plays them back. A {@link MovieRecorder} is offered the mask that
 * was in force for every finished frame -- the same frames the two rewind rings are fed on, since
 * all three have to agree about what a frame is -- and a {@link Movie} being played supplies that
 * mask instead of the keyboard. Both of them move the pad from the immediate path to a latch on this
 * thread, once a frame, which is what {@code KeyboardInput.setLatching} is for: a key that reached
 * the controller half way through a frame would be recorded as belonging to a frame it was only half
 * of.
 * <p>
 * Emulation cannot happen on the event dispatch thread: a machine that never stops running would
 * never let the EDT paint a menu. So this is the only thread that touches the NES, and the only
 * thing it touches on the UI side is {@link ScreenComponent#present(int[], int)}, which is written to
 * be called from here. {@link #start()} and {@link #stop()} are for the EDT, and anything else
 * the UI wants done to the machine -- a reset, a debug switch -- goes through {@link #post} and
 * runs here, between frames.
 * <p>
 * The one deliberate exception is the CHR viewer, which reads the mapper's character memory and
 * the PPU's palette RAM from the EDT while this thread runs. It is a debug window watching memory
 * whose reads cannot tear; a stale tile in it would not be worth a lock on every pattern fetch.
 * <p>
 * The debugger window is not a second exception, and reads the machine under a <em>stricter</em>
 * rule rather than a looser one: only from inside {@link #setStopListener}'s callback, which
 * {@link #halt} hands to the event dispatch thread after stopping the machine. That handoff is what
 * makes everything this thread did visible to that one. A debugger shows a machine at a moment in
 * its execution rather than a picture of memory, and values read at different instants would not be
 * a slightly stale picture -- they would be a machine that never existed.
 *
 * @see com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame
 * @see com.github.dimiro1.mynes.ui.debugger.DebuggerFrame
 */
public class EmulatorRunner {
    private static final Logger logger = System.getLogger("EMU");

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

    /**
     * How many frames apart the rewind states are taken, and so how many frames each display tick
     * gives back while the key is held.
     * <p>
     * Two rather than one, which is three improvements for one cost. The capture is half as often,
     * so it takes a little over a millisecond a frame instead of nearly three. The same memory holds
     * twice as much game. And, since a tick gives back one state either way, <strong>the rewind runs
     * at twice speed</strong> -- undoing five seconds takes two and a half rather than five, which
     * is the difference between a feature and a chore.
     * <p>
     * The cost is that letting go of the key lands on an even frame, so it can be one frame away
     * from the exact moment somebody wanted. At sixty frames a second that is sixteen milliseconds
     * of a game they are about to play differently anyway.
     */
    private static final int REWIND_INTERVAL = 2;

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

    /**
     * Where the breakpoints live. Owned by the window rather than by this, because it outlives every
     * machine the window builds: a power cycle that forgot every breakpoint would be infuriating,
     * since a power cycle is often exactly how you get back to one.
     */
    private final Debugger debugger;

    /**
     * The last few seconds of the machine, or null when the setting asked for none.
     * <p>
     * Owned by the runner rather than by the window, unlike the debugger: a history belongs to the
     * machine that lived it, and carrying one across a power cycle would let somebody rewind into a
     * game that had already been switched off and on again.
     */
    private final @Nullable Rewind rewind;

    /**
     * The sound that went with the history, kept alongside it and never without it. Null exactly
     * when {@link #rewind} is.
     */
    private final @Nullable RewindAudio rewindAudio;

    private volatile boolean running;

    /**
     * How many frames of the game have gone past since this machine was switched on.
     * <p>
     * Written only by this thread and read only by the event dispatch thread, which is the whole of
     * why a plain {@code volatile} is enough: one writer, and a reader that wants the newest value
     * rather than a consistent pair of them. It is what the window's frame rate is measured from --
     * two readings and the time between them -- so it must never go backwards while a machine
     * lives, which is why nothing resets it.
     * <p>
     * <strong>Rewound frames count too</strong>, and that is the decision here worth writing down.
     * Nothing is re-emulated going backwards -- the picture travels inside the state -- so a count
     * of frames <em>emulated</em> would read zero for as long as the key is held, which is a status
     * bar saying the machine has stopped while the game is visibly moving. What this counts is
     * frames of the game that went past, in whichever direction they went, which is the question
     * "is it keeping up" is really asking.
     */
    private volatile long framesRun;

    /**
     * Whether the rewind key is being held. Written by the event dispatch thread and read here, so
     * the loop picks it up at the next frame boundary rather than mid-frame -- the same handoff as
     * {@link #paused} and for the same reason.
     */
    private volatile boolean rewinding;

    /**
     * Written by the event dispatch thread when somebody uses the Pause item, and by this thread
     * when {@link #halt} stops the machine at a breakpoint. Two writers, which is safe because it is
     * {@code volatile} and because in every real sequence the two are ordered by the user's own
     * actions -- but {@link #resume()} has to be one posted command rather than two calls for the
     * same reason.
     */
    private volatile boolean paused;

    /**
     * Told, on the event dispatch thread, whenever the machine stops somewhere it was asked to.
     */
    private volatile Consumer<Debugger.Stop> stopListener;

    /**
     * Told, on the event dispatch thread, when a movie reaches its last frame -- so the window can
     * give the keyboard back and take the word off the title bar.
     */
    private volatile Runnable playbackEndedListener;

    /**
     * The session being written down, or null. Emulation thread only, like everything below it:
     * every way in goes through {@link #post}.
     */
    private @Nullable MovieRecorder recorder;

    /**
     * The session being played back, or null.
     */
    private @Nullable Movie playing;

    /**
     * Which frame of {@link #playing} runs next, counted from the movie's own start.
     */
    private long playCursor;

    /**
     * The mask latched for the frame now running, which is what gets written down when it finishes.
     * Held rather than read twice, so the frame a recorder is told about is exactly the frame the
     * game saw.
     */
    private int pendingMask;

    /**
     * Where a latched mask comes from while recording: the keyboard, in practice. Never null, so the
     * loop has nothing to check -- a machine with no keyboard pointed at it records nothing pressed,
     * which is true.
     */
    private IntSupplier inputSource = () -> 0;

    /**
     * Whether the next time round the loop starts a frame rather than resuming one a breakpoint
     * stopped part way through. The guard on the latch: changing what the game is holding half way
     * through a frame would be a frame nobody could record or replay honestly.
     */
    private boolean atFrameBoundary = true;

    /**
     * How fast to run. Written from the event dispatch thread and read here, so the loop picks a
     * change up at the next frame boundary rather than mid-frame.
     */
    private volatile EmulationSpeed speed = EmulationSpeed.NORMAL;

    private Thread thread;

    /**
     * @param rewindFrames how many frames of history to keep so the machine can be run backwards
     *                     through them, or 0 for a machine that keeps none -- which costs one null
     *                     check a frame and nothing else. Frames rather than states: the ring holds
     *                     one state per {@link #REWIND_INTERVAL} of them.
     */
    public EmulatorRunner(
            final NES nes,
            final ScreenComponent screen,
            final Debugger debugger,
            final int rewindFrames) {
        this.nes = nes;
        this.screen = screen;
        this.debugger = debugger;
        this.frameNanos = nes.getRegion().frameNanos();

        var states = rewindFrames / REWIND_INTERVAL;

        if (states >= Rewind.MINIMUM_CAPACITY) {
            this.rewind = new Rewind(states, REWIND_INTERVAL);

            // Counted in frames rather than states, because sound is not something there can be
            // every other one of: the ring has to hold the frames in between as well, or the rewind
            // would play half the seconds it was showing.
            this.rewindAudio = new RewindAudio(states * REWIND_INTERVAL);
        } else {
            this.rewind = null;
            this.rewindAudio = null;
        }
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
     * How many frames of the game have gone past since this machine was switched on. Safe to read
     * from any thread, and the only thing a frame rate needs: two readings and the time between
     * them.
     */
    public long getFramesRun() {
        return framesRun;
    }

    /**
     * Runs the machine backwards for as long as this is true, one frame of history per display tick.
     * Takes effect within a frame.
     * <p>
     * A no-op on a machine keeping no history, so the key can be wired up unconditionally and the
     * setting decides whether anything happens.
     */
    public void setRewinding(final boolean rewinding) {
        this.rewinding = rewinding;
    }

    /**
     * Told whenever the machine stops at a breakpoint, a watchpoint, a step or a Break, on the event
     * dispatch thread and with the machine already stopped.
     */
    public void setStopListener(final Consumer<Debugger.Stop> listener) {
        this.stopListener = listener;
    }

    /**
     * Told whenever a movie reaches its last frame, on the event dispatch thread. The machine is
     * still running: the last frame of a replay is followed by the next frame of a game somebody is
     * now playing themselves.
     */
    public void setPlaybackEndedListener(final Runnable listener) {
        this.playbackEndedListener = listener;
    }

    // ==================================================================================== movies

    /**
     * Where the mask comes from while a movie is being recorded, latched once a frame on this
     * thread. Wired to the keyboard per machine, the way the controller and the rewind key are.
     */
    public void setFrameInputSource(final IntSupplier source) {
        post(() -> inputSource = source);
    }

    /**
     * Starts writing the session down.
     * <p>
     * Always anchored, unlike the command line's: somebody who has just decided to record something
     * is hardly ever sitting on a machine that has not run yet, and a menu item that behaved
     * differently depending on whether they were would be a menu item nobody could predict.
     *
     * @param codes the Game Genie codes in the slot, pinned into the movie's header. The window
     *              refuses to change them while this is running, since a movie whose header names
     *              one set and whose frames were played against another cannot be replayed.
     */
    public void startRecording(final List<GameGenieCode> codes) {
        post(() -> recorder = MovieRecorder.anchoredAt(nes, codes));
    }

    /**
     * Stops, and writes what was recorded.
     *
     * @param onFailure told on the event dispatch thread if the file could not be written, since
     *                  {@link #post} has nothing to hand an exception back on.
     */
    public void stopRecording(final Path path, final Consumer<Exception> onFailure) {
        post(() -> {
            if (recorder == null) {
                return;
            }

            var movie = recorder.movie();
            recorder = null;

            try {
                movie.write(path);
                logger.log(Level.INFO, "wrote a " + movie.frameCount() + " frame movie to "
                        + path.getFileName());
            } catch (IOException | MovieException e) {
                logger.log(Level.ERROR, "could not write the movie", e);
                SwingUtilities.invokeLater(() -> onFailure.accept(e));
            }
        });
    }

    /**
     * Puts the machine where the movie starts and plays it from there.
     * <p>
     * A state change rather than a plain command: the anchor replaces the machine wholesale, and
     * what the sound card is still holding belongs to a game that is no longer running.
     */
    public void startPlayback(final Movie movie) {
        postStateChange(() -> {
            try {
                movie.applyAnchor(nes);
            } catch (MovieException e) {
                // The window checked the header before opening the file, so this is close to
                // impossible -- and a machine left half started would be worse than a log line.
                // Told anyway, or the window sits with the keyboard muted waiting for a playback
                // that never began.
                logger.log(Level.ERROR, "could not start the movie", e);
                notePlaybackEnded();
                return;
            }

            playing = movie;
            playCursor = 0;
            atFrameBoundary = true;

            // A movie of no frames is legal and boring, and it is over before the first one runs.
            // Ended here rather than left to the loop, so the window is told either way.
            if (playCursor >= playing.frameCount()) {
                endPlayback();
            }
        });
    }

    /**
     * Gives up on a movie part way through, leaving the machine wherever it had got to. What
     * reaching for the rewind key does, and what the menu item does.
     */
    public void stopPlayback() {
        post(this::endPlayback);
    }

    /**
     * The console's Reset button, and the one way the window presses it.
     * <p>
     * One posted command rather than two, because a recorder has to be told before the machine is
     * and the two threads cannot be trusted to keep that order between them.
     */
    public void reset() {
        post(() -> {
            if (recorder != null) {
                recorder.reset();
            }

            nes.reset();
        });
    }

    /**
     * The machine has been replaced wholesale by something nobody played their way to -- a loaded
     * slot. Called from inside the runnable that did it, so a recording in progress starts again
     * from where the state put it rather than carrying on describing a timeline that no longer
     * leads anywhere.
     */
    public void noteMachineJumped() {
        if (recorder != null) {
            recorder.jumped(nes);
        }

        atFrameBoundary = true;
    }

    /**
     * Runs exactly one instruction, whether the machine is paused or not.
     */
    public void stepInstruction() {
        post(debugger::stepInstruction);
    }

    /**
     * Runs to the end of the frame, stopping earlier for anything that would have stopped it anyway.
     */
    public void stepFrame() {
        post(debugger::stepFrame);
    }

    /**
     * Stops the machine at the next instruction boundary -- so within a frame, since a machine on
     * the fast path finishes the frame it is in first.
     */
    public void breakNow() {
        post(debugger::halt);
    }

    /**
     * Lets the machine go, and forgets whatever the debugger was still waiting for.
     * <p>
     * One posted command rather than two calls, because the order matters and the two threads
     * cannot be trusted to keep it: the queue is drained at the top of every time round, before the
     * pause is looked at, so this can never leave a frame running against a halt that has not been
     * cleared yet.
     */
    public void resume() {
        post(() -> {
            debugger.run();
            paused = false;
        });
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
        logger.log(Level.INFO, "emulation started");

        var ppu = nes.getPPU();
        var apu = nes.getAPU();

        try {
            audio.open();

            // The floor of the history is the machine as it was switched on, so that rewinding all
            // the way back lands on the power-on screen rather than on whatever the first frame of
            // the game happened to be.
            if (rewind != null) {
                rewind.capture(nes);
            }

            var speed = this.speed;
            var deadline = System.nanoTime();
            var nextPresent = deadline + frameNanos;
            var lastFrame = ppu.getFrame();
            var wasPaused = false;
            var wasRewinding = false;

            while (running) {
                runPendingCommands();

                // Normally a no-op -- nothing has been clocked since this was last assigned. It
                // matters when the frame counter has just moved backwards, which both a loaded save
                // state and a rewound frame do: the loop below waits for the counter to *change*, so
                // a stale value here would satisfy it after a single tick and present a torn frame.
                lastFrame = ppu.getFrame();

                // Asked before the pause is looked at, because a step is the one thing that runs a
                // machine that is not running.
                var stepping = debugger.isStepping();

                if (paused && !stepping) {
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

                // After the pause branch, so pause wins: a frozen machine that could still be
                // rewound would be two ideas about what the screen is showing. And guarded against
                // stepping for the reason the pause branch is, since a step is the one thing that
                // runs a machine that is not running.
                if (rewinding && rewind != null && !stepping) {
                    // Reaching for rewind during a replay is how somebody says "let me take it from
                    // here": the movie stops and the machine is theirs. Anything else would be a
                    // replay fighting the player for the same frames.
                    if (playing != null) {
                        endPlayback();
                    }

                    if (!wasRewinding) {
                        // What the card is holding is up to a tenth of a second of a game that is
                        // now running the other way. Dropped for the reason a pause drops it.
                        audio.flush();
                        screen.setRewinding(true);
                        wasRewinding = true;
                    }

                    // Read here rather than taken from the snapshot below, which is only refreshed
                    // on the forward path: reaching for Fast Forward without letting go of rewind is
                    // how the game runs backwards at speed, and it has to take effect while it is
                    // being held rather than once it has been let go of.
                    var rewindSpeed = this.speed;
                    var wasOn = ppu.getFrame();
                    var moved = rewind.rewind(nes, 1);

                    if (moved > 0) {
                        // The frames that step actually gave back, which is two most of the time and
                        // one on the first step off a frame with no state of its own. Counted rather
                        // than assumed, so the sound is exactly the sound of the frames the picture
                        // has just gone back over.
                        var given = (int) (wasOn - ppu.getFrame());

                        framesRun += given;

                        // Frames rather than the states the call above answered in: this ring keeps
                        // one every other frame, so the two numbers are different here in a way they
                        // are not in a headless session.
                        if (recorder != null) {
                            recorder.rewound(nes, given);
                        }

                        atFrameBoundary = true;

                        // Backwards, and at whatever rate the rewind is running -- so two frames of
                        // it are handed over in the time the card plays one. Never blocking, for the
                        // reason fast forward never blocks: there is no way to give a sound card
                        // audio faster than real time, and waiting for it would slow the rewind down
                        // to the speed of the thing being undone. What does not fit is dropped, so
                        // this comes out chopped, which is very much what rewinding sounds like.
                        audio.write(samples, rewindAudio.take(given, samples), false);

                        // Nothing is re-emulated: the picture arrives with the state. What is left
                        // is deciding whether to hand it over, and that is the forward path's
                        // arithmetic unchanged -- otherwise UNLIMITED would ask the display for
                        // several thousand pictures a second while it drained the ring.
                        var now = System.nanoTime();

                        if (rewindSpeed == EmulationSpeed.NORMAL || now - nextPresent >= 0) {
                            screen.present(ppu.getFrameBuffer(), ppu.getFramePhase());

                            nextPresent += frameNanos;
                            if (nextPresent - now < 0) {
                                nextPresent = now + frameNanos;
                            }
                        }
                    }

                    // A ring that has run out waits a whole frame whatever the speed. There is
                    // nothing left to go back to, so the oldest picture simply stays up -- and
                    // UNLIMITED, which does not wait at all, would otherwise spin against it.
                    LockSupport.parkNanos(
                            moved > 0 ? rewindSpeed.frameNanos(nes.getRegion()) : frameNanos);
                    deadline = System.nanoTime();
                    continue;
                }

                if (wasRewinding) {
                    // The other edge, and the same reasoning: the card is holding up to a tenth of
                    // a second of a game running backwards, which stopped being true the moment the
                    // key came up. What comes out of the speaker should be what is on the screen.
                    audio.flush();
                    screen.setRewinding(false);
                    wasRewinding = false;
                }

                // Skipped while stepping: the machine is still stopped, the card was emptied when
                // it stopped, and a speed schedule belongs to a loop that is running.
                if (!paused) {
                    wasPaused = false;

                    if (this.speed != speed) {
                        // Both schedules start again from here rather than carrying a deadline
                        // written in the old speed's units -- which, coming off unlimited, is not a
                        // deadline that was being kept at all.
                        speed = this.speed;
                        deadline = System.nanoTime();
                        nextPresent = deadline + frameNanos;
                    }
                }

                // The pad, changed exactly once per frame and on this thread, whenever a movie is
                // involved. Skipped on a frame that is being resumed after a breakpoint stopped it
                // part way through: latching again in flight would change what the game is holding
                // inside a single frame, which is a frame neither a recording nor a replay could
                // describe.
                if (atFrameBoundary) {
                    if (playing != null) {
                        if (playing.resetsAt(playCursor)) {
                            nes.reset();
                        }

                        pendingMask = playing.buttonsAt(playCursor);
                        nes.getController1().setButtons(pendingMask);
                    } else if (recorder != null) {
                        pendingMask = inputSource.getAsInt();
                        nes.getController1().setButtons(pendingMask);
                    }
                }

                Debugger.Stop stop = null;

                if (debugger.isArmed()) {
                    stop = runWatchedFrame(lastFrame);
                } else {
                    // The PPU has no frame-complete callback; its frame counter is the signal. One
                    // tick is three dots, so this can overshoot the boundary by up to two of them --
                    // at most the first pixel of the next frame arrives early, on scanline 0, which
                    // the overscan crop hides anyway.
                    do {
                        nes.tick();
                    } while (ppu.getFrame() == lastFrame);
                }

                var completed = ppu.getFrame() != lastFrame;

                atFrameBoundary = completed;

                // Every frame that finished, however it finished -- stepped, halted, fast
                // forwarded -- for the reason the two rings below are fed on exactly those: a frame
                // is a frame whatever ran it, and a rate that skipped the stepped ones would say a
                // machine somebody is stepping through is not running at all.
                if (completed) {
                    framesRun++;
                }

                // Drained up here rather than at the two places below that used to do it, because
                // the rewind ring has to be given the sound of a frame before anything decides
                // whether that frame's sound is going to be played. A frame that stopped part way
                // through is left alone, exactly as it was: there is no finished frame of sound in
                // it, and the APU's own ring holds several frames' worth of slack.
                var sampleCount = completed ? apu.drainSamples(samples) : 0;

                // Every frame that finished, wherever it finished -- stepped, halted, fast
                // forwarded. One place, above everything below that might skip the rest of the
                // loop, because the ring's newest entry has to describe the machine as it stands --
                // and because the two rings must be fed on exactly the same frames or the sound
                // would come from a different second of the game than the picture.
                if (completed && rewind != null) {
                    rewind.capture(nes);
                    rewindAudio.capture(samples, sampleCount);
                }

                // The same gate, deliberately: a movie, the rewind ring and the sound ring have to
                // be fed on exactly the same frames or none of the three describes the same second
                // of the game as the others.
                if (completed && recorder != null) {
                    recorder.frame(pendingMask);
                }

                if (completed && playing != null) {
                    playCursor++;

                    if (playCursor >= playing.frameCount()) {
                        // Straight back to the keyboard, with no pause and no dialog: the frame
                        // after the last frame of a replay is the first frame of a game somebody is
                        // playing.
                        endPlayback();
                    }
                }

                if (stop != null) {
                    halt(stop);
                }

                if (!completed) {
                    // Half a frame, stopped part way through. Nothing finished to put on the
                    // screen -- the last whole frame is still up, which is what a paused machine
                    // shows anyway -- and nothing to pace against. The deadline is left where it
                    // was; the pause branch resets it on the next time round.
                    continue;
                }

                if (stop != null) {
                    // A stepped or halted frame still goes on the screen. Its sound does not: one
                    // frame of it played on its own is a click, and a machine stepped a frame at a
                    // time would be a metronome of them. It was drained above rather than left, so
                    // the APU's ring does not carry this frame across the stop and play it on the
                    // far side -- and the rewind ring kept it, so going back over a stepped frame
                    // still has its sound.
                    screen.present(ppu.getFrameBuffer(), ppu.getFramePhase());
                    continue;
                }

                // A frame's worth of sound, handed over before the picture is: at normal speed
                // this blocks until the card has room, which is the other half of the pacing
                // below, and there is no sense making the audio wait on a frame that is only
                // going to be dropped anyway. Fast forwarding cannot block -- there is no way to
                // hand a sound card audio faster than real time -- so what does not fit is lost,
                // and fast forward sounds chopped rather than sped up.
                audio.write(samples, sampleCount, speed == EmulationSpeed.NORMAL);

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
                    screen.present(ppu.getFrameBuffer(), ppu.getFramePhase());

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
            logger.log(Level.ERROR, "emulation failed at frame " + ppu.getFrame(), t);
        } finally {
            audio.close();

            // A machine torn down mid-rewind would otherwise leave the marker painted over the next
            // one -- or over an empty window, if this was the last.
            screen.setRewinding(false);
        }

        logger.log(Level.INFO, "emulation stopped");
    }

    /**
     * One frame, clocked an instruction at a time so that a breakpoint can stop the machine part
     * way through one.
     * <p>
     * Only reached when the debugger has something to look for. The ordinary path above is left
     * exactly as it was, because a check that belongs here -- one an instruction, about 1.8 million
     * a second -- is one a machine nobody is debugging should not pay for.
     * <p>
     * {@link NES#step()} runs to the next instruction boundary, so the end of the frame is noticed
     * up to one instruction late rather than up to two dots late: seven cycles usually, and around
     * five hundred when the step swallows an OAM DMA transfer. That is under five scanlines of the
     * next frame drawn into the buffer before it is shown, and all of them are inside the eight
     * {@link com.github.dimiro1.mynes.video.FrameRenderer#OVERSCAN_TOP} takes off the top.
     *
     * @return why it stopped, or null if the frame simply finished.
     */
    private Debugger.Stop runWatchedFrame(final long lastFrame) {
        var ppu = nes.getPPU();
        var cpu = nes.getCPU();

        while (ppu.getFrame() == lastFrame) {
            var wasPC = cpu.getPC();

            nes.step();

            var stop = debugger.afterInstruction(cpu.getPC(), wasPC);

            if (stop != null) {
                return stop;
            }
        }

        return debugger.afterFrame(cpu.getPC());
    }

    /**
     * Stops the machine where it stands, and says why.
     * <p>
     * Sets the same flag the Pause item does, because it means the same thing: this loop must not
     * clock the machine. Everything that follows from it -- the card emptied, the last whole frame
     * left on the screen, posted commands still running -- is the pause branch's doing.
     * <p>
     * The listener is told on the event dispatch thread, and the hop is made here rather than left
     * to whoever registered because this is the one place that can be sure of it. It is also what
     * makes reading the machine from that thread legal afterwards: everything this thread did before
     * the handoff is visible to the one that takes it.
     */
    private void halt(final Debugger.Stop stop) {
        paused = true;

        logger.log(Level.DEBUG, "stopped: " + stop);

        var listener = stopListener;

        if (listener != null) {
            SwingUtilities.invokeLater(() -> listener.accept(stop));
        }
    }

    /**
     * Drops the movie and tells the window, which is what gives the keyboard back.
     * <p>
     * The listener is told on the event dispatch thread, and the hop is made here rather than left
     * to whoever registered, for the reason {@link #halt} makes it here: this is the one place that
     * can be sure of it.
     */
    private void endPlayback() {
        if (playing == null) {
            return;
        }

        logger.log(Level.INFO, "playback ended at frame " + nes.getPPU().getFrame()
                + ", " + playCursor + " of " + playing.frameCount() + " frames played");

        playing = null;
        playCursor = 0;

        notePlaybackEnded();
    }

    private void notePlaybackEnded() {
        var listener = playbackEndedListener;

        if (listener != null) {
            SwingUtilities.invokeLater(listener);
        }
    }

    private void runPendingCommands() {
        Runnable command;
        while ((command = commands.poll()) != null) {
            command.run();
        }
    }
}
