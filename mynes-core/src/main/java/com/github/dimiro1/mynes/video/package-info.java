/**
 * What the picture looks like once it has left the chip -- drawn, and described.
 * <p>
 * The 2C02 writes colour indices, and turning those into colours on a particular surface is a
 * question about televisions rather than about silicon: which measurement of the chip's output to
 * believe, and how much of the frame a bezel would have covered. That is why it lives out here
 * rather than in {@link com.github.dimiro1.mynes.PPU}, and why both the window and the headless
 * mode come through it rather than each answering the question their own way.
 * <p>
 * One thing in particular is worth only being written down once, and it is the reason this package
 * exists rather than a couple of shared constants: <b>where the picture ends</b>. Four places want
 * to walk the visible scanlines -- the window's drawing, a PNG, a frame hash, a colour count -- and
 * four spellings of {@code SCREEN_HEIGHT - 8} is three chances for one of them to disagree about
 * what is on screen. {@link com.github.dimiro1.mynes.video.FrameRenderer#OVERSCAN_TOP} and
 * {@link com.github.dimiro1.mynes.video.FrameRenderer#VISIBLE_BOTTOM} are the only answer.
 */
package com.github.dimiro1.mynes.video;
