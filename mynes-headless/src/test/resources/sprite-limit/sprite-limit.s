; sprite-limit.nes -- all sixty four sprites on one scanline.
;
; The cartridge --hack unlimited-sprites is demonstrated against, in the form a person reads rather
; than the form a program builds. No shipped game does this: the hardware draws eight sprites a
; scanline and every cartridge is written to stay under that, so the overflow the hack exists to
; undo is exactly what a real game spends its effort avoiding. Punch-Out!!'s first fight peaks at
; seven sprites on a line and Battletoads' first level at eight.
;
; What it draws is one row of sixty four solid blocks four pixels apart, cycling the four sprite
; palettes so the individual sprites can be counted. With the sprite limit in force the row is
; thirty six pixels wide, because only the first eight are drawn. With it lifted the row crosses
; the screen.
;
; Nothing assembles this, and the .nes beside it was not built from it. SpriteLimitROM, in this
; module's test sources, is the assembler: it emits the same program as bytes, SpriteLimitTests
; holds the cartridge against it, and running it is how the cartridge is rewritten. That is why the
; build needs no assembler and why this file has no way to break it.
;
; So this is here to be read, and to be changed by somebody who would rather edit a program than
; count branch offsets by hand. Doing that means changing SpriteLimitROM to match and rerunning it;
; its Javadoc has the command.
;
; To assemble this file instead, the syntax is asm6's -- .base, .pad and .dsb are its directives --
; and it writes the .nes in one pass, with no linker config:
;
;     asm6 sprite-limit.s sprite-limit.nes
;
; asm6 is one C file by loopy, maintained as asm6f at github.com/freem/asm6f, and builds with
; "cc -O2 -o asm6 asm6f.c". Another assembler will want this file rewritten: ca65 in particular
; spells the directives differently and needs a linker config to place the bank.

; ---------------------------------------------------------------- iNES header

    .db "NES", $1A
    .db 1                       ; one 16KB program bank
    .db 1                       ; one 8KB character bank
    .dsb 10, $00                ; mapper 0, horizontal mirroring, no battery, no trainer

; ---------------------------------------------------------------- program bank

; An NROM cartridge with a single bank mirrors it into both halves of $8000-$FFFF, and the 6502
; reads its vectors from the top of memory, so the bank is assembled for the upper copy.
    .base $C000

SPRITES         = 64
SPRITE_Y        = 100           ; the same line for all of them; a sprite is drawn on the one below
SPRITE_SPACING  = 4             ; four pixels apart, so all sixty four fit across the 256

xpos            = $10           ; the X coordinate, counted up as the loop goes

reset:
    sei
    cld
    ldx #$40
    stx $4017                   ; no APU frame interrupt
    ldx #$FF
    txs
    inx                         ; X = 0 from here down
    stx $2000
    stx $2001
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

; Thirty two bytes of palette RAM, copied in wholesale.
    lda #$3F
    sta $2006
    lda #$00
    sta $2006
    ldx #$00
copypalette:
    lda palette,x
    sta $2007
    inx
    cpx #32
    bne copypalette

; Sixty four sprites, written a byte at a time through $2004 rather than by DMA, because the
; address walks itself and there is nothing here in a hurry.
    lda #$00
    sta $2003                   ; OAMADDR at the start of OAM
    sta xpos                    ; and the first sprite at the left edge
    ldx #$00
sprite:
    lda #SPRITE_Y
    sta $2004                   ; Y
    lda #$01
    sta $2004                   ; tile 1, the solid block
    txa
    and #$03                    ; a different palette every fourth sprite
    sta $2004                   ; attributes
    lda xpos
    sta $2004                   ; X
    clc
    adc #SPRITE_SPACING
    sta xpos
    inx
    cpx #SPRITES
    bne sprite

    lda #$14                    ; sprites on, left column included
    sta $2001

; Nothing left to do, and a 6502 with nothing to do has to be given somewhere to do it.
forever:
    jmp forever

; ---------------------------------------------------------------- data

; On a page of its own, far enough past the code that the two cannot meet however the code grows.
    .pad $C100
palette:
    .db $0F, $00, $10, $30
    .db $0F, $00, $10, $30
    .db $0F, $00, $10, $30
    .db $0F, $00, $10, $30
    .db $0F, $16, $27, $18      ; only the sprite half matters: colour 1 of each palette is a
    .db $0F, $1A, $2A, $3A      ; colour of its own, so that a row of blocks cycling through them
    .db $0F, $12, $22, $32      ; can be told apart from one long block
    .db $0F, $14, $24, $34

    .pad $FFFA
    .dw forever                 ; NMI, which is never enabled
    .dw reset
    .dw forever                 ; IRQ, which never fires

; ---------------------------------------------------------------- character bank

; Not addressed by the CPU at all, so the base goes back to zero for it.
    .base $0000

    .dsb 16, $00                ; tile 0, blank
    .db $FF, $FF, $FF, $FF, $FF, $FF, $FF, $FF   ; tile 1, the low bit plane solid
    .dsb 8, $00                 ; and the high one left off, which is colour 1
    .dsb 8192 - 32, $00
