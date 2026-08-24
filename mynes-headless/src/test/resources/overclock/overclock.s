; overclock.nes -- a game whose logic does not fit in a frame.
;
; The cartridge --hack overclock is demonstrated against, in the form a person reads rather than the
; form a program builds. What the hack undoes is a main loop overrunning its frame: the next NMI
; arrives with the last frame's work unfinished, the game skips a turn, and the picture stutters.
; Super Mario Bros. 3 and Gradius do it under load, but only under load, in places a test cannot
; reliably reach and only for a handful of frames at a time. So this does it on purpose and does it
; every time.
;
; The program is a game with one job: count how many times it can get through a fixed pile of work.
; The pile is about 42500 cycles, which is 1.43 NTSC frames -- so on the hardware it finishes one lap
; every *two* frames, because the wait at the end of a lap always ends on an NMI and the loop is
; phase-locked to them. Give it 131 extra scanlines a frame and the frame becomes 44671 cycles, the
; pile fits, and it manages a lap per frame. Give it 66 -- half as many -- and the frame is 37282
; cycles, which is not enough, and it is back to one every two.
;
;     java -jar mynes.jar --headless --rom overclock.nes --frames 300 --dump ram
;     java -jar mynes.jar --headless --rom overclock.nes --frames 300 --dump ram \
;         --hack overclock=131
;
; Two counters in zero page say what happened, both sixteen bit and little endian:
;
;     $00-$01   frames, counted by the NMI handler
;     $02-$03   laps, counted by the main loop
;
; The screen says the same thing without a debugger. Rendering is never switched on, so the whole
; picture is the backdrop -- and with rendering off the backdrop is read from wherever the VRAM
; address happens to point rather than from $3F00, which is the "background palette hack" real games
; use to flash the screen. So the NMI leaves the address at $3F00 + (laps & 7) and the screen changes
; colour once per finished lap, with no $2007 write to undo. A report's video.frameChanges then
; counts laps for free.
;
; Nothing assembles this, and the .nes beside it was not built from it. OverclockROM, in this
; module's test sources, is the assembler: it emits the same program as bytes, OverclockRunTests
; holds the cartridge against it, and running it is how the cartridge is rewritten. That is why the
; build needs no assembler and why this file has no way to break it.
;
; So this is here to be read, and to be changed by somebody who would rather edit a program than
; count branch offsets by hand. Doing that means changing OverclockROM to match and rerunning it;
; its Javadoc has the command.
;
; To assemble this file instead, the syntax is asm6's -- .base, .pad and .dsb are its directives --
; and it writes the .nes in one pass, with no linker config:
;
;     asm6 overclock.s overclock.nes
;
; asm6 is one C file by loopy, maintained as asm6f at github.com/freem/asm6f, and builds with
; "cc -O2 -o asm6 asm6f.c". Another assembler will want this file rewritten: ca65 in particular
; spells the directives differently and needs a linker config to place the bank.

; ---------------------------------------------------------------- iNES header

    .db "NES", $1A
    .db 1                       ; one 16KB program bank
    .db 1                       ; one 8KB character bank, empty -- nothing is ever rendered
    .dsb 10, $00                ; mapper 0, horizontal mirroring, no battery, no trainer

; ---------------------------------------------------------------- program bank

; An NROM cartridge with a single bank mirrors it into both halves of $8000-$FFFF, and the 6502
; reads its vectors from the top of memory, so the bank is assembled for the upper copy.
    .base $C000

OUTER           = 33            ; laps of the delay loop; 33 comes to 42439 cycles

frames          = $00           ; sixteen bit, counted by the NMI
laps            = $02           ; sixteen bit, counted by the main loop
tick            = $04           ; 1 when a frame has been drawn since the loop cleared it

reset:
    sei
    cld
    ldx #$40
    stx $4017                   ; no APU frame interrupt
    ldx #$FF
    txs
    inx                         ; X = 0 from here down
    stx $2000
    stx $2001                   ; rendering stays off for good
    stx $4010

; The two VBlanks every cartridge waits for. The PPU ignores $2000, $2001, $2005 and $2006 until
; the beam first reaches the pre-render line, about 29658 CPU cycles in, so anything written before
; this would be dropped on the floor.
vblank1:
    bit $2002
    bpl vblank1
vblank2:
    bit $2002
    bpl vblank2

; Eight background colours, one per palette cell the NMI can point the VRAM address at.
    lda #$3F
    sta $2006
    lda #$00
    sta $2006
    ldx #$00
copypalette:
    lda palette,x
    sta $2007
    inx
    cpx #8
    bne copypalette

; Both counters and the flag between the two halves of the program.
    lda #$00
    sta frames
    sta frames+1
    sta laps
    sta laps+1
    sta tick

    lda #$80                    ; NMI on; rendering is still off
    sta $2000

; ---------------------------------------------------------------- main, one lap of the game

; The pile of work is a delay loop because what the work is does not matter -- only that it is the
; same every lap and that it does not fit in a frame. 33 laps of 1283 cycles, plus the branches, is
; 42439; the rest of the lap brings it to about 42500, and an NTSC frame is 29780.
;
; None of the branches below crosses a page, which is load bearing rather than incidental: a taken
; branch that crossed one would cost an extra cycle every time round the inner loop.
main:
    ldy #OUTER
outer:
    ldx #$00
inner:
    dex
    bne inner                   ; 256 times round, 1279 cycles
    dey
    bne outer

    inc laps                    ; one more lap finished
    bne waitforframe
    inc laps+1

; Wait for the next picture. Clearing the flag before waiting is what makes this a wait for the
; *next* NMI rather than an acknowledgement of the last one -- and it is why a lap takes a whole
; number of frames however long the work took.
waitforframe:
    lda #$00
    sta tick
waitloop:
    lda tick
    beq waitloop
    jmp main

; ---------------------------------------------------------------- data

; On a page of its own, far enough past the code that the two cannot meet however the code grows.
    .pad $C100
palette:
    .db $0F, $16, $2A, $12, $28, $24, $1C, $30

; ---------------------------------------------------------------- interrupts

; Nothing can reach this: the program starts with sei and switches the APU's frame interrupt off,
; and an NROM cartridge has no interrupt of its own. It is here so that an interrupt nobody can
; explain returns instead of running the NMI handler and counting a frame that did not happen.
    .pad $C1FF
irq:
    rti

    .pad $C200
nmi:
    pha                         ; X and Y are the main loop's; A is not
    inc frames                  ; one more frame
    bne setframeflag
    inc frames+1
setframeflag:
    lda #$01
    sta tick                    ; let the main loop go on

; Leave the VRAM address inside palette RAM, at the cell this lap's number names. With rendering off
; that cell *is* the backdrop, so the whole screen becomes that colour and stays it until the next
; lap -- and nothing has to be written back.
    bit $2002                   ; and put the $2006 latch back to first
    lda #$3F
    sta $2006
    lda laps
    and #$07
    sta $2006

    pla
    rti                         ; which puts the flags back too

    .pad $FFFA
    .dw nmi
    .dw reset
    .dw irq

; ---------------------------------------------------------------- character bank

; Empty, and it stays that way: rendering is never switched on, so there is no tile to put in it. It
; is here because a cartridge with no character bank at all is a cartridge with character RAM, which
; is a different thing to have to explain.
    .base $0000
    .dsb 8192, $00
