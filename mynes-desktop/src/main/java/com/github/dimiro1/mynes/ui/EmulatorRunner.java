package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.debug.Usage;
import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.ui.music.MusicRecorder;
import com.github.dimiro1.mynes.state.Movie;
import com.github.dimiro1.mynes.state.MovieException;
import com.github.dimiro1.mynes.state.MovieRecorder;
import com.github.dimiro1.mynes.state.Rewind;
import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;

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
 * @see com.github.dimiro1.mynes.ui.controlpanel.ControlPanelFrame
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

    /**
     * How many finished frames go by between readouts: a quarter of a second on either console, the
     * same interval every debug view in the front end sweeps on, and as fast as anybody reads a
     * number off a screen.
     */
    private static final int READOUT_FRAMES = 15;

    /**
     * How many points of the last frame's sound a readout carries, for the scope to draw.
     * <p>
     * A frame is 735 samples on NTSC and this takes every third, which is as much of a waveform as
     * a couple of hundred pixels can show. Decimated rather than averaged deliberately: a scope is
     * for seeing the shape of a wave, and averaging is a low pass that would take the corners off
     * the square one the pulses actually make.
     */
    private static final int SCOPE_SAMPLES = 245;

    private final NES nes;
    private final ScreenComponent screen;
    private final AudioOutput audio;

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
     * Who wants the machine described at a frame boundary, or null when nobody does.
     * <p>
     * Null is the point of it, and it is the {@link Debugger#isArmed()} rule again: nothing is read
     * off the machine and nothing is posted to the event dispatch thread while the control panel is
     * closed, which is nearly always. Volatile because the window sets it and this thread reads it.
     */
    private volatile @Nullable Consumer<Readout> frameObserver;

    /**
     * Frames left before the next readout, counted down rather than taken as a remainder of the
     * frame number: a rewind moves that by two at a time and would step over any multiple.
     */
    private int untilReadout;

    /**
     * A decimated copy of the last frame's sound, refilled only while somebody is watching.
     */
    private final short[] scope = new short[SCOPE_SAMPLES];

    /**
     * The same frame, one trace per voice, taken from the chip rather than from what was played:
     * these are what each voice put into the mixer, where {@link #scope} is what came out.
     */
    private final short[][] traces =
            new short[APUChannel.values().length][SCOPE_SAMPLES];

    /**
     * A frame of one voice, borrowed by {@link #fillScope} and never handed anywhere.
     */
    private final short[] voice = new short[AUDIO_BUFFER_SAMPLES];

    /**
     * How often the game has been reading the pads, frame by frame. The one thing in a readout that
     * has to be counted as the frames go past rather than read off the machine at the end of them.
     */
    private final PadPolling polling = new PadPolling();

    /**
     * How much of its frame the program is using and how much of the machine's memory, which like
     * the polling above it is measured as the frames go past rather than read off the machine at
     * the end of them. Fed from the write hook and at the boundary; see {@link Usage}.
     */
    private final Usage usage = new Usage();

    /**
     * The music being written down, or null when nobody asked for any. Emulation thread only, like
     * the recorder above it, and for the same reason: it is asked what the chip is playing at a
     * frame boundary, which is a question only this thread can ask.
     */
    private @Nullable MusicRecorder music;

    /**
     * What the machine did to its hardware during the frame now running, in the order it did it.
     * Filled by the debugger's bus hooks and emptied at every boundary.
     */
    private final EventLog events = new EventLog();

    /**
     * Whether the reads are being recorded as well as the writes, which is the Events tab's own
     * tick. Volatile because that tick is on the event dispatch thread and this is read here.
     */
    private volatile boolean eventReads;

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
     * The masks latched for the frame now running, which are what get written down when it finishes.
     * Held rather than read twice, so the frame a recorder is told about is exactly the frame the
     * game saw.
     */
    private int pendingMask1;
    private int pendingMask2;

    /**
     * Where a latched mask comes from while recording: the keyboard, in practice, one supplier per
     * port. Never null, so the loop has nothing to check -- a machine with no keyboard pointed at it
     * records nothing pressed, which is true.
     */
    private IntSupplier inputSource1 = () -> 0;
    private IntSupplier inputSource2 = () -> 0;

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
     * @param rewindFrames    how many frames of history to keep so the machine can be run backwards
     *                        through them, or 0 for a machine that keeps none -- which costs one
     *                        null check a frame and nothing else. Frames rather than states: the
     *                        ring holds one state per {@link #REWIND_INTERVAL} of them.
     * @param audioLatencyMs  how much sound to keep the card holding, which is decided here rather
     *                        than posted later: {@link AudioOutput#open()} is the first thing this
     *                        thread does and the size of a line cannot be changed once it is open.
     */
    public EmulatorRunner(
            final NES nes,
            final ScreenComponent screen,
            final Debugger debugger,
            final int rewindFrames,
            final int audioLatencyMs) {
        this.nes = nes;
        this.screen = screen;
        this.debugger = debugger;
        this.audio = new AudioOutput(audioLatencyMs);
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
     * interruptible and can be another latency on top -- 60 milliseconds by default. Still under a
     * tenth of a second, only on the frame a machine happens to be torn down on, and rarer than it
     * was now that the rate control keeps the card half empty rather than letting it fill.
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
     * Whether the emulation thread is going.
     * <p>
     * Which is the question "is the machine mine to touch, or do I have to post?". A caller holding
     * a stopped runner owns the machine outright -- {@link #stop()} joins the thread before it
     * returns -- and posting to one would queue work that never runs.
     */
    public boolean isRunning() {
        return thread != null;
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
     * Starts writing down what the sound chip is playing.
     * <p>
     * A frame at a time rather than four times a second like everything else the front end reads,
     * because a melody moves faster than that -- see {@link MusicRecorder}. It costs three voice
     * reads and three comparisons a frame, paid only while somebody is recording.
     *
     * @param region which console this is, since a frame is 16.6ms on one and 20ms on the other.
     */
    public void startMusic(final Region region) {
        post(() -> music = new MusicRecorder(region));
    }

    /**
     * Stops, and writes what was played to {@code path}.
     * <p>
     * The file is written on this thread rather than handed back, for the reason the movie's is:
     * the recorder belongs to the thread that filled it, and a caller that took it away would be
     * reading it while this one was still adding to it.
     *
     * @param whenDone told how many frames were written, on the event dispatch thread, or -1 if
     *                 nothing was playing or the file could not be written.
     */
    public void stopMusic(final Path path, final LongConsumer whenDone) {
        post(() -> {
            var writing = music;

            music = null;

            if (writing == null || writing.isEmpty()) {
                logger.log(Level.INFO, "nothing was playing, so no music was written");
                SwingUtilities.invokeLater(() -> whenDone.accept(-1));

                return;
            }

            try {
                writing.writeTo(path);
                logger.log(Level.INFO, "wrote " + writing.frames() + " frames of music to " + path);
                SwingUtilities.invokeLater(() -> whenDone.accept(writing.frames()));
            } catch (IOException e) {
                logger.log(Level.ERROR, "could not write the music", e);
                SwingUtilities.invokeLater(() -> whenDone.accept(-1));
            }
        });
    }

    /**
     * Draws the frame again with whatever the picture switches now say, for a machine that is not
     * running and so would not draw one by itself.
     * <p>
     * <b>Why this cannot be a redraw.</b> Show Background, Show Sprites and Unlimited Sprites take
     * part where the PPU <em>composes</em> a pixel, and what the framebuffer keeps is what came out
     * of that -- so the background under a sprite is not in it, and no amount of looking at the
     * picture again will produce one without the sprites. The frame has to be rendered a second
     * time. That is the difference between these three and the palette, the filters and the two
     * crops, which {@link ScreenComponent} redraws from the colour indices it kept and which have
     * always worked while paused.
     * <p>
     * <b>So the machine is run, and then put back.</b> A state is taken, two frame boundaries are
     * gone through -- to the end of whatever frame the machine was standing in, then one whole one,
     * since a partial frame would leave the top of the picture as it was -- the picture is handed
     * over, and the state goes back. The machine ends byte-identical, which is the same claim the
     * rewind rests on. What it costs is about seven milliseconds, once, on a click.
     * <p>
     * Two things have to be swept up after it. The frames are run {@link Debugger#unwatched}, since
     * a watchpoint that latched during one would report itself on the next real instruction as a
     * stop nobody asked for. And the samples they made are drained and dropped: the state puts the
     * chip back but the ring between it and the sound card is deliberately not in a state, so they
     * would otherwise be played.
     * <p>
     * <b>The picture is one frame ahead of the one it replaces</b>, and there is no way for it not
     * to be. It is the same scene -- the machine has not moved -- so the difference is a frame of
     * animation, which is what "the same picture without the sprites" costs.
     */
    public void redrawPicture() {
        post(this::renderTheFrameAgain);
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
     * Asks to be handed the machine at a frame boundary, four times a second, on the event dispatch
     * thread. Null asks to stop being handed it.
     * <p>
     * A boundary rather than a timer, because what a gauge shows is a dozen scalars and reading
     * those one at a time off a running machine gives a machine that never existed -- see
     * {@link Readout}. Four times a second rather than sixty, because that is as fast as anybody
     * reads a number and sixty would be fifty-six posts to the event dispatch thread that nobody
     * looked at.
     */
    public void setFrameObserver(final @Nullable Consumer<Readout> observer) {
        this.frameObserver = observer;

        // The meters, which cost the mixer a null check on its hottest line and nothing else while
        // nobody is looking. Posted rather than set here, so the field the mixer reads is only ever
        // written by the thread that reads it.
        var apu = nes.getAPU();
        var wanted = observer != null;

        post(() -> apu.setPeakTracking(wanted));

        armTheHooks();
    }

    /**
     * Starts the memory map again, which is the Usage tab's own button.
     * <p>
     * Posted, like every other change to a running machine: the meter belongs to the thread
     * clocking it and the button is on the event dispatch thread. Only the map is forgotten -- what
     * the last frame cost is a measurement of that frame and has nothing to be started again.
     */
    public void forgetUsage() {
        post(usage::forget);
    }

    /**
     * Records the reads as well as the writes, which is the Events tab's own tick.
     * <p>
     * Its own switch rather than something that follows the panel being open, because it is the one
     * part of this that a machine can feel: the read hook sees every instruction fetch. Everything
     * else rides on the write hook, which a game passes a few hundred times a frame.
     */
    public void setEventReads(final boolean reads) {
        this.eventReads = reads;

        armTheHooks();
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
     * Where the masks come from while a movie is being recorded, latched once a frame on this
     * thread. Wired to the keyboard per machine, the way the controllers and the rewind key are.
     * <p>
     * Both ports together, for the reason {@code MovieRecorder.frame} takes both: a caller that
     * could wire one of them is a caller that can forget the other, and a movie missing a player
     * replays as a session nobody played.
     */
    public void setFrameInputSource(final IntSupplier player1, final IntSupplier player2) {
        post(() -> {
            inputSource1 = player1;
            inputSource2 = player2;
        });
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
     * The machine is not told: a muted APU still runs, still raises its interrupts and still fills
     * the card with the silence that keeps the rate control fed, because a game that sounded
     * different depending on the volume would be a different game. Which is also why this is not
     * {@link #setChannelMuted} with all five voices named -- that one changes what the samples are,
     * and this one changes only how loudly they are played.
     */
    public void setMuted(final boolean muted) {
        post(() -> audio.setMuted(muted));
    }

    /**
     * How loud to play it. Takes effect within a frame, and does not lift a mute.
     */
    public void setVolume(final Volume volume) {
        post(() -> audio.setVolume(volume));
    }

    /**
     * Keeps one of the APU's five voices out of the mixer, or lets it back in.
     * <p>
     * Unlike {@link #setMuted} this one <em>is</em> the machine being told, and is the only thing on
     * this class that changes the samples rather than the playback: it is the sound half of the
     * Debug menu's layer switches, and it is how to find out which voice a noise belongs to.
     * Nothing a game can observe moves -- see {@link APUChannel}.
     */
    public void setChannelMuted(final APUChannel channel, final boolean muted) {
        var apu = nes.getAPU();

        post(() -> apu.setChannelMuted(channel, muted));
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

                        // Going backwards is still the frame counter moving, and a dashboard frozen
                        // at whatever number a rewind started from would be the one thing on it
                        // anybody would notice was wrong.
                        observeFrames(given);

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
                    if (wasPaused) {
                        // The other edge, and it matters more than the one going in: what the card
                        // is holding is the tail of a pause, and behind it is an empty queue. The
                        // rate control can only refill one at a couple of hundred samples a second,
                        // so a resume that left it empty would click its way through the next ten
                        // seconds of the game. Flushing lays a fresh cushion of silence down.
                        audio.flush();
                        wasPaused = false;
                    }

                    if (this.speed != speed) {
                        // Both schedules start again from here rather than carrying a deadline
                        // written in the old speed's units -- which, coming off unlimited, is not a
                        // deadline that was being kept at all.
                        speed = this.speed;
                        deadline = System.nanoTime();
                        nextPresent = deadline + frameNanos;
                    }
                }

                // The pads, changed exactly once per frame and on this thread, whenever a movie is
                // involved. Skipped on a frame that is being resumed after a breakpoint stopped it
                // part way through: latching again in flight would change what the game is holding
                // inside a single frame, which is a frame neither a recording nor a replay could
                // describe.
                if (atFrameBoundary) {
                    if (playing != null) {
                        if (playing.resetsAt(playCursor)) {
                            nes.reset();
                        }

                        pendingMask1 = playing.buttonsAt(playCursor);
                        pendingMask2 = playing.buttons2At(playCursor);

                        hold(pendingMask1, pendingMask2);
                    } else if (recorder != null) {
                        pendingMask1 = inputSource1.getAsInt();
                        pendingMask2 = inputSource2.getAsInt();

                        hold(pendingMask1, pendingMask2);
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

                // After the drain, because the readout carries a slice of exactly the sound this
                // frame produced -- and the drain is not a clock, so the machine is still standing
                // where the frame left it.
                if (completed) {
                    fillScope(sampleCount);
                    notePads();
                    noteUsage();
                    noteMusic();
                    observeFrames(1);

                    // After the readout rather than before it, and that order is the whole of how
                    // the Events tab gets a frame rather than a fragment of one: what the log holds
                    // at this moment is exactly the frame that has just ended.
                    events.startFrame();
                }

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
                    recorder.frame(pendingMask1, pendingMask2);
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

                // A frame's worth of sound, handed over before the picture is. At normal speed it
                // is rate controlled against how full the card is and written blocking, which the
                // rate control is what stops from ever actually blocking; the deadline below is
                // what paces the loop, and two things pacing one loop is what the drift the rate
                // control exists to remove was made of. Fast forwarding is not paced by anything --
                // there is no way to hand a sound card audio faster than real time -- so what does
                // not fit is lost, and it sounds chopped rather than sped up.
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
     * {@link com.github.dimiro1.mynes.video.FrameRenderer#OVERSCAN_TOP} takes off the top -- so
     * they are only ever seen by somebody who has asked, under Settings &gt; Show Overscan, to see
     * the lines that margin is spent out of.
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

    /**
     * Hands the machine over, if anybody asked for it and enough frames have gone by.
     * <p>
     * Called from the two places {@code framesRun} moves and from nowhere else: a frame is a frame
     * whatever ran it -- stepped, halted, fast forwarded, rewound -- and this is read at exactly
     * the moment the last one finished, which is the moment nothing in the machine is half written.
     */
    private void observeFrames(final int frames) {
        var observer = frameObserver;

        if (observer == null) {
            return;
        }

        untilReadout -= frames;

        if (untilReadout > 0) {
            return;
        }

        untilReadout = READOUT_FRAMES;

        // Built here and handed over whole. A lambda that read the machine on the other thread
        // would be reading a running one, which is the whole thing Readout exists to avoid -- and
        // the scope is cloned for the same reason: this thread refills its own next frame.
        var traced = new ArrayList<short[]>(traces.length);

        for (var trace : traces) {
            traced.add(trace.clone());
        }

        var readout = Readout.of(
                nes,
                scope.clone(),
                List.copyOf(traced),
                polling.snapshot(),
                events.snapshot(),
                usage.snapshot(nes.getBus().getMapper().prgRAM().length));

        // The meters' window starts again here rather than inside the reading, so that the
        // debugger's stop snapshot -- which goes through the same record -- cannot empty them.
        nes.getAPU().clearPeaks();

        SwingUtilities.invokeLater(() -> observer.accept(readout));
    }

    /**
     * Hands both pads the masks the frame about to run is being played with.
     * <p>
     * The two together and nowhere else, because a movie is one row of frames rather than one per
     * port: a replay that set the pads on different frames would be playing back a session nobody
     * played.
     */
    private void hold(final int player1, final int player2) {
        nes.getController1().setButtons(player1);
        nes.getController2().setButtons(player2);
    }

    /**
     * Counts what the game did to the pads in the frame that has just finished.
     * <p>
     * Every forward frame rather than every readout, unlike everything else here, because what it
     * measures is a difference between consecutive frames: a lag frame seen once every fifteen
     * would be fifteen frames of the game reported as one. Cheap enough for that -- four counter
     * reads and a subtraction -- and only while somebody is watching, which is the
     * {@link #frameObserver} rule.
     * <p>
     * <b>Not from the rewind path</b>, which is the one place {@code framesRun} moves without any
     * frame being run. Nothing is re-emulated going backwards, so the counters do not move either,
     * and every frame handed back would be counted as a frame the game failed to read the pad in.
     * The frame number is what says so: the first frame after a rewind is not one more than the
     * last one counted, and {@link PadPolling} starts again rather than measuring across the gap.
     */
    private void notePads() {
        if (frameObserver == null) {
            return;
        }

        polling.frameEnded(framesRun, nes.getController1(), nes.getController2());
    }

    /**
     * Closes off the frame the program has just had, for the same reason and under the same rules
     * as {@link #notePads()}: how much of a frame was used is a difference between two boundaries,
     * so measuring only every fifteenth frame would report fifteen frames of a game as one, and the
     * frame number is what tells a run of frames from two with a gap between them.
     * <p>
     * The writes it is counting arrive on the bus hook in between; this is only the boundary.
     */
    private void noteUsage() {
        if (frameObserver == null) {
            return;
        }

        var cpu = nes.getCPU();

        usage.frameEnded(framesRun, cpu.getRunCycles(), cpu.getStalledCycles());
    }

    /**
     * Points the debugger's bus hooks at the event log and the usage meter, or takes them off.
     * <p>
     * Posted rather than done here, because the debugger belongs to the thread clocking the machine
     * and both callers are on the event dispatch thread -- the panel being shown, and its reads
     * tick. Off whenever nobody is watching, which is the same rule the readout keeps and is nearly
     * always.
     * <p>
     * <b>Neither slows the machine down.</b> A sink is not a breakpoint: the driver's fast loop is
     * untouched, and what the two cost between them is one hook on a bus the game already crosses.
     */
    private void armTheHooks() {
        var wanted = frameObserver != null;
        var reads = eventReads;

        post(() -> {
            debugger.setEventSink(wanted ? events::record : null, reads);
            debugger.setUsage(wanted ? usage : null);
        });
    }

    /**
     * Writes down what the chip was playing on the frame that has just finished.
     * <p>
     * Unlike {@link #notePads()} this is not behind the observer: a recording is something somebody
     * asked for and it must go on whether or not a window is open to watch it. Unlike the movie
     * recorder it is not fed on the rewind path either, and what that means is written down in
     * {@link MusicRecorder}: a passage played twice was heard twice.
     */
    private void noteMusic() {
        if (music != null) {
            music.frame(nes.getAPU());
        }
    }

    /**
     * Takes every third sample of the frame that has just finished, or leaves the last frame's
     * where it is when there is nothing to take -- a stepped frame drains no sound.
     * <p>
     * Only while somebody is watching, which is the {@link #frameObserver} rule: nothing here runs
     * for a panel that is closed.
     */
    private void fillScope(final int count) {
        if (frameObserver == null || count == 0) {
            return;
        }

        var step = Math.max(1, count / SCOPE_SAMPLES);

        decimate(samples, count, step, scope);

        // The chip's own record of the same frame, asked for at exactly the length just drained so
        // that a voice's trace and the mixed one are the same samples.
        var wanted = Math.min(count, voice.length);

        for (var channel : APUChannel.values()) {
            nes.getAPU().trace(channel, voice, wanted);
            decimate(voice, wanted, step, traces[channel.ordinal()]);
        }
    }

    private static void decimate(
            final short[] from, final int count, final int step, final short[] into) {

        for (var i = 0; i < into.length; i++) {
            var at = i * step;

            into[i] = at < count ? from[at] : 0;
        }
    }

    /**
     * See {@link #redrawPicture()}, which is where all of the reasoning is.
     */
    void renderTheFrameAgain() {
        // A running machine draws the next frame within about seventeen milliseconds anyway, and
        // it will draw it with the new setting. This is only for one that has stopped.
        if (!paused) {
            return;
        }

        var ppu = nes.getPPU();
        var taken = new ByteArrayOutputStream();

        try {
            SaveState.write(nes, taken);
        } catch (IOException e) {
            logger.log(Level.WARNING, "could not redraw the picture", e);
            return;
        }

        debugger.unwatched(() -> {
            runToFrameBoundary();
            runToFrameBoundary();
        });

        screen.present(ppu.getFrameBuffer(), ppu.getFramePhase());

        try {
            SaveState.read(nes, new ByteArrayInputStream(taken.toByteArray()));
        } catch (IOException e) {
            // Nothing to be done about it here, and saying so matters: the machine has just been
            // run two frames further than anybody asked and cannot be put back.
            logger.log(Level.ERROR, "could not put the machine back after redrawing", e);
        }

        // The chip is back where it was, but the queue between it and the card is not in a state --
        // see APU.sampleRing in SaveStateCompletenessTests -- so those two frames are still in it.
        nes.getAPU().drainSamples(samples);

        // Nor is the count of cycles a transfer stole, so the processor's executed-cycle count came
        // back from the state a little short of where it left. Those two frames were not the game
        // running and there is nothing to measure across them either way.
        usage.unmeasured();
    }

    /**
     * Clocks the machine until the frame counter moves, which is the only signal the PPU gives that
     * a frame is over -- the same do-while the main loop's fast path uses.
     */
    private void runToFrameBoundary() {
        var ppu = nes.getPPU();
        var was = ppu.getFrame();

        while (ppu.getFrame() == was) {
            nes.tick();
        }
    }

    private void runPendingCommands() {
        Runnable command;
        while ((command = commands.poll()) != null) {
            command.run();
        }
    }
}
