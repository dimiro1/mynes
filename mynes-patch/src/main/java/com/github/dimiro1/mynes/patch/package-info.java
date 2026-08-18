/**
 * Binary patches, applied to a file's bytes before anything else looks at them.
 * <p>
 * A romhack is handed out as a patch rather than as a ROM, because the ROM is somebody else's
 * copyright and the difference between it and the hack is not.
 * {@link com.github.dimiro1.mynes.patch.IPSPatch} applies one to an array of bytes and hands back
 * another array, which is the whole of the API: the file on disk is never written to, and there is
 * no patched copy of it for anybody to keep track of afterwards.
 * <p>
 * Nothing here knows what it is patching. IPS is a diff format from 1990 that says nothing about its
 * subject -- a ROM, a save file, a disk image -- and that is why this is a module of its own rather
 * than a package inside the console: a patcher that could see a cartridge would eventually be handed
 * one.
 * <p>
 * The one thing a caller has to know is that offsets are counted from the front of the file. A patch
 * cut against a dump with a header must be applied to a dump with that header, and one cut against a
 * headerless dump written sixteen bytes into a headered one will corrupt it quietly and thoroughly.
 * Which of the two a patch was made for is nowhere in the patch, so it is not guessed at here.
 */
package com.github.dimiro1.mynes.patch;
