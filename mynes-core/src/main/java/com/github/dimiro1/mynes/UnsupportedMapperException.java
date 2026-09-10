package com.github.dimiro1.mynes;

import java.util.Map;

/**
 * A cartridge whose board nobody has written.
 * <p>
 * <b>The number is the least useful half of the answer.</b> A mapper number names a circuit rather
 * than a chip -- what the cartridge does with the fifteen address lines and eight data lines the
 * port gives it -- and there are hundreds of them where this runs twelve. Somebody told their file
 * wants mapper 69 has to go and look up what 69 is before they know whether it is a famous board, a
 * pirate multicart, or a header somebody filled in wrongly; told it wants Sunsoft FME-7, they
 * already know.
 * <p>
 * So the boards are named. Not all of them -- the tail is hundreds of bootleg conversions nobody
 * loads on purpose -- but every one a real release shipped on, which is the set a collection can
 * actually hand this. The names are the ones NESdev's list prints, because that is what somebody
 * will search for next.
 *
 * @see <a href="https://www.nesdev.org/wiki/List_of_mappers">NESdev: list of mappers</a>
 */
public class UnsupportedMapperException extends RuntimeException {

    /**
     * What each unsupported board is called, for the numbers a real cartridge carries.
     * <p>
     * Only boards this does <em>not</em> run: the twelve it does never reach here, and an entry for
     * one of them would be a second place to update when a thirteenth is written.
     */
    private static final Map<Integer, String> BOARDS = Map.ofEntries(
            Map.entry(5, "MMC5"),
            Map.entry(16, "the Bandai FCG board"),
            Map.entry(18, "Jaleco SS8806"),
            Map.entry(19, "Namco 163"),
            Map.entry(20, "the Famicom Disk System"),
            Map.entry(21, "Konami VRC4a or VRC4c"),
            Map.entry(22, "Konami VRC2a"),
            Map.entry(23, "Konami VRC2b or VRC4e"),
            Map.entry(24, "Konami VRC6a"),
            Map.entry(25, "Konami VRC4b or VRC4d"),
            Map.entry(26, "Konami VRC6b"),
            Map.entry(34, "BNROM or NINA-001"),
            Map.entry(64, "Tengen RAMBO-1"),
            Map.entry(68, "Sunsoft-4"),
            Map.entry(69, "Sunsoft FME-7 or 5B"),
            Map.entry(73, "Konami VRC3"),
            Map.entry(75, "Konami VRC1"),
            Map.entry(76, "Namco 109"),
            Map.entry(85, "Konami VRC7"),
            Map.entry(118, "TxSROM, an MMC3 variant"),
            Map.entry(119, "TQROM, an MMC3 variant"),
            Map.entry(180, "UNROM wired the way Crazy Climber has it"),
            Map.entry(185, "CNROM with protection diodes"),
            Map.entry(206, "DxROM, or Namco 118"),
            Map.entry(210, "Namco 175 or 340"),
            Map.entry(232, "the Camerica Quattro board"));

    /**
     * Creates a new exception for an unsupported mapper.
     *
     * @param mapperNumber the mapper number the header asked for
     * @param filename     the ROM that asked for it, which is what somebody has to go and look at
     */
    public UnsupportedMapperException(final int mapperNumber, final String filename) {
        super(message(mapperNumber, filename));
    }

    private static String message(final int mapperNumber, final String filename) {
        var board = BOARDS.get(mapperNumber);

        return filename + " wants mapper " + mapperNumber
                + (board == null ? "" : ", " + board)
                + ", which this does not run.";
    }
}
