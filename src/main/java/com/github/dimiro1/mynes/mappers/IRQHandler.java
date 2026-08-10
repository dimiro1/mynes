package com.github.dimiro1.mynes.mappers;

/**
 * The /IRQ line as the cartridge drives it.
 * <p>
 * The line is level triggered and shared: the cartridge pulls it low and holds it there, and the
 * CPU keeps seeing the request for as long as it is held, even while the interrupt disable flag
 * is masking it. So this is a level, not a pulse -- a mapper that has raised an interrupt must
 * release it again when its acknowledge register is written.
 *
 * @see <a href="https://www.nesdev.org/wiki/IRQ">NESdev: IRQ</a>
 */
@FunctionalInterface
public interface IRQHandler {
    /**
     * @param asserted true to pull the line low, false to let it go.
     */
    void setIRQLine(boolean asserted);
}
