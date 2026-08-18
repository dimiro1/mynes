# Third party software in MyNES

MyNES itself is MIT, and the terms are in `LICENSE` next to this file.

`mynes.jar` is a fat jar: two libraries are unpacked into it and shipped as part of it. This is where
their terms are recorded, because the unpacking loses them. FlatLaf's own copy of the Apache licence
lands at `META-INF/LICENSE` inside the jar, where it reads as though it covered the whole thing,
and MigLayout ships no licence file in either of its jars at all.

## FlatLaf 3.7.2

The look and feel, and the system file dialog behind **File > Open...** Also the source of the
native libraries the jar carries for Windows, Linux and macOS, which is what lets one download run
on all three.

- Apache License 2.0, as declared by its POM and as written out in full at `META-INF/LICENSE`
  inside `mynes.jar`.
- https://www.formdev.com/flatlaf/ -- https://github.com/JFormDesigner/FlatLaf

## MigLayout 11.4.3

The layout manager, used by the dialogs. Two artifacts, `miglayout-swing` and the
`miglayout-core` it pulls in with it.

- BSD. That is what `miglayout-parent-11.4.3.pom` declares, pointing at
  http://www.debian.org/misc/bsd.license for the text; nothing more specific ships with the jars.
- http://www.miglayout.com/ -- https://github.com/mikaelgrev/miglayout

## What is not in here

Three more dependencies are in `pom.xml` and none of them reaches the jar. The JetBrains annotations
are `provided`, because `@Nullable` is read by javac and by an IDE and by nothing at run time.
Jackson and JUnit are `test`; Jackson parses the Tom Harte processor fixtures, and the headless mode
writes its own reports rather than borrowing it.

The palettes bundled under `/palettes` in the jar are data rather than software, and are neither
FlatLaf's nor MigLayout's. Their sources, terms and credits are in the `PROVENANCE` file beside them,
which is inside `mynes.jar` and also in the repository at
`mynes-core/src/main/resources/palettes/PROVENANCE`.
