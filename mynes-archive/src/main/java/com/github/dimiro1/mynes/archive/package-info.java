/**
 * Zip files, opened in memory for the one thing inside them somebody wanted.
 * <p>
 * A collection of ROMs arrives as one zip per game, so the file a person picks out of a chooser is
 * as often the archive as the cartridge. {@link com.github.dimiro1.mynes.archive.Archive} reads one
 * out of an array of bytes and hands back the files inside it as more arrays of bytes, which is the
 * whole of the API: nothing is unpacked to disk, so there is no temporary copy of somebody's ROM
 * left behind and nothing to tidy up afterwards.
 * <p>
 * Nothing here knows what it is holding. Zip is a container from 1989 that says nothing about its
 * contents, and that is why this is a module of its own rather than a package inside the console --
 * the same reason {@link com.github.dimiro1.mynes.patch} is one. The callers are the front ends,
 * which are what know that a cartridge is called {@code .nes}; this knows only how to be asked for
 * files whose names end a certain way.
 * <p>
 * It is deliberately the JDK's own zip support and nothing else. Deflate is in {@code java.util.zip}
 * and has been since 1.1, so the whole of this is a wrapper thin enough to read in one sitting, and
 * the fat jar carries no third library for it.
 */
package com.github.dimiro1.mynes.archive;
