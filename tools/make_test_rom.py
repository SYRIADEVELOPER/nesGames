#!/usr/bin/env python3
"""Builds nesgames_test.nes: an original, public-domain NROM test program written for nesGames QA.
Behaviour: plays a constant ~440 Hz square wave; backdrop colour reflects controller 1:
  none=blue(0x21)  A=red(0x16)  B=green(0x2A)  other buttons=yellow(0x28).
That gives machine-checkable evidence of video output, audio output and input handling."""
import sys

code = bytearray(); labels = {}; fixups = []
def emit(*b): code.extend(b)
def label(n): labels[n] = 0xC000 + len(code)
def absw(n): fixups.append(('abs', len(code), n)); emit(0, 0)
def rel(n): fixups.append(('rel', len(code), n)); emit(0)

label('reset')
emit(0x78, 0xD8, 0xA2, 0xFF, 0x9A)                 # sei; cld; ldx #$ff; txs
emit(0xA9, 0x00, 0x8D, 0x00, 0x20, 0x8D, 0x01, 0x20)  # PPUCTRL=PPUMASK=0
label('vw1'); emit(0x2C, 0x02, 0x20, 0x10); rel('vw1')
label('vw2'); emit(0x2C, 0x02, 0x20, 0x10); rel('vw2')
for reg, val in ((0x15, 0x01), (0x00, 0xBF), (0x01, 0x00), (0x02, 0xFD), (0x03, 0x00)):
    emit(0xA9, val, 0x8D, reg, 0x40)                  # APU pulse 1: duty 50%, const vol 15, ~440 Hz
emit(0xA9, 0x80, 0x8D, 0x00, 0x20)                    # NMI on
emit(0xA9, 0x0A, 0x8D, 0x01, 0x20)                    # show background (incl. left column)
label('loop'); emit(0x4C); absw('loop')

label('nmi')
emit(0x48)                                           # pha
emit(0xA9, 0x01, 0x8D, 0x16, 0x40, 0xA9, 0x00, 0x8D, 0x16, 0x40)  # strobe pad
emit(0xA2, 0x08)
label('rd'); emit(0xAD, 0x16, 0x40, 0x4A, 0x26, 0x00, 0xCA, 0xD0); rel('rd')
emit(0xA0, 0x21, 0xA5, 0x00, 0xF0); rel('setc')       # ldy first: LDY would clobber Z. no buttons -> blue
emit(0xA0, 0x16, 0x0A, 0xB0); rel('setc')             # A (bit7) -> red
emit(0xA0, 0x2A, 0x0A, 0xB0); rel('setc')             # B (bit6) -> green
emit(0xA0, 0x28)                                      # anything else -> yellow
label('setc')
emit(0xAD, 0x02, 0x20)                               # reset address latch
emit(0xA9, 0x3F, 0x8D, 0x06, 0x20, 0xA9, 0x00, 0x8D, 0x06, 0x20)
emit(0x8C, 0x07, 0x20)                               # sty $2007 -> universal backdrop
emit(0xA9, 0x00, 0x8D, 0x06, 0x20, 0x8D, 0x06, 0x20, 0x8D, 0x05, 0x20, 0x8D, 0x05, 0x20)
emit(0x68, 0x40)                                     # pla; rti
label('irq'); emit(0x40)

for kind, off, n in fixups:
    t = labels[n]
    if kind == 'abs': code[off], code[off + 1] = t & 0xFF, t >> 8
    else:
        d = t - (0xC000 + off + 1)
        assert -128 <= d <= 127, n
        code[off] = d & 0xFF

prg = bytearray(code) + bytearray(0x4000 - len(code))
for i, n in enumerate(('nmi', 'reset', 'irq')):
    prg[0x3FFA + i * 2] = labels[n] & 0xFF; prg[0x3FFB + i * 2] = labels[n] >> 8
header = b'NES\x1a' + bytes([1, 1, 0, 0]) + bytes(8)
rom = header + prg + bytes(0x2000)
out = sys.argv[1] if len(sys.argv) > 1 else 'nesgames_test.nes'
open(out, 'wb').write(rom)
print(out, len(rom), 'bytes')
