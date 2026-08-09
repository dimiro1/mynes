package com.github.dimiro1.mynes.cpu;

import com.github.dimiro1.mynes.CPU;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes a single {@link HarteCase} against a real {@link CPU} wired to a {@link RecordingBus}.
 * <p>
 * One runner owns one CPU and one bus and is reused for every case of an opcode, so the cost per
 * case is the case itself and not the setup.
 */
final class HarteCaseRunner {

    /**
     * How closely the result has to match the fixture.
     */
    enum Strictness {
        /**
         * Final registers, final memory, and the number of cycles consumed.
         */
        STATE,

        /**
         * Everything {@link #STATE} checks, plus the exact ordered sequence of bus accesses:
         * one access per cycle, at the address and with the value the hardware would have used.
         */
        BUS_TRACE,
    }

    private final RecordingBus bus = new RecordingBus();
    private final CPU cpu = new CPU(bus);

    /**
     * Runs one case.
     *
     * @return null when the case passed, otherwise a diagnostic message describing every
     * mismatch found.
     */
    String run(final HarteCase testCase, final Strictness strictness) {
        var initial = testCase.initial();

        bus.reset();
        for (var entry : initial.ram()) {
            bus.preload(entry[0], entry[1]);
        }

        // Cycles start at zero so the counter afterwards is the number of cycles the
        // instruction took.
        cpu.loadState(new CPU.State(
                initial.a(), initial.x(), initial.y(), initial.s(), initial.pc(), initial.p(), 0
        ));

        try {
            cpu.step();
        } catch (RuntimeException e) {
            return report(testCase, List.of("threw " + e));
        }

        var problems = new ArrayList<String>();
        var expected = testCase.expected();
        var actual = cpu.getState();

        compare(problems, "pc", expected.pc(), actual.pc(), 4);
        compare(problems, "a", expected.a(), actual.a(), 2);
        compare(problems, "x", expected.x(), actual.x(), 2);
        compare(problems, "y", expected.y(), actual.y(), 2);
        compare(problems, "s", expected.s(), actual.sp(), 2);
        compare(problems, "p", expected.p(), actual.p(), 2);
        compare(problems, "cycles", testCase.cycles().size(), (int) actual.cycles(), 0);

        for (var entry : expected.ram()) {
            var value = bus.peek(entry[0]);
            if (value != entry[1]) {
                problems.add(String.format(
                        "ram $%04X: expected $%02X, was $%02X", entry[0], entry[1], value
                ));
            }
        }

        // The fixture lists every address the instruction is allowed to touch, so a write
        // anywhere else is a bug the loop above cannot see.
        for (var activity : bus.activities()) {
            if (!activity.read() && !isTracked(expected.ram(), activity.address())) {
                problems.add(String.format(
                        "wrote $%02X to $%04X, an address the case does not touch",
                        activity.value(), activity.address()
                ));
            }
        }

        if (strictness == Strictness.BUS_TRACE) {
            compareBusTrace(problems, testCase);
        }

        return problems.isEmpty() ? null : report(testCase, problems);
    }

    private void compareBusTrace(final List<String> problems, final HarteCase testCase) {
        var expected = testCase.cycles();
        var actual = bus.activities();

        if (expected.size() != actual.size()) {
            problems.add(String.format(
                    "bus accesses: expected %d (one per cycle), was %d",
                    expected.size(), actual.size()
            ));
        }

        for (var i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            var want = expected.get(i);
            var got = actual.get(i);

            if (want.address() != got.address()
                    || want.value() != got.value()
                    || want.isRead() != got.read()) {
                problems.add(String.format(
                        "cycle %d: expected [%04X, %02X, %s], was %s",
                        i + 1, want.address(), want.value(), want.type(), got
                ));
            }
        }
    }

    private static boolean isTracked(final List<int[]> ram, final int address) {
        for (var entry : ram) {
            if (entry[0] == address) {
                return true;
            }
        }
        return false;
    }

    private static void compare(
            final List<String> problems,
            final String name,
            final int expected,
            final int actual,
            final int hexDigits
    ) {
        if (expected == actual) {
            return;
        }

        if (hexDigits == 0) {
            problems.add(String.format("%s: expected %d, was %d", name, expected, actual));
        } else {
            problems.add(String.format(
                    "%s: expected $%0" + hexDigits + "X, was $%0" + hexDigits + "X",
                    name, expected, actual
            ));
        }
    }

    private String report(final HarteCase testCase, final List<String> problems) {
        var initial = testCase.initial();
        var expected = testCase.expected();
        var message = new StringBuilder();

        message.append("case \"").append(testCase.name()).append("\"\n");
        for (var problem : problems) {
            message.append("    ").append(problem).append('\n');
        }

        message.append("    ").append(String.format(
                "%-8s %-6s %-4s %-4s %-4s %-4s %-4s", "", "pc", "s", "a", "x", "y", "p"
        )).append('\n');
        message.append("    ").append(registers("initial", initial.pc(), initial.s(),
                initial.a(), initial.x(), initial.y(), initial.p())).append('\n');
        message.append("    ").append(registers("expected", expected.pc(), expected.s(),
                expected.a(), expected.x(), expected.y(), expected.p())).append('\n');

        var actual = cpu.getState();
        message.append("    ").append(registers("actual", actual.pc(), actual.sp(),
                actual.a(), actual.x(), actual.y(), actual.p())).append('\n');

        message.append("    recorded bus trace (").append(bus.activities().size()).append("):\n");
        message.append("        ").append(bus.activities()).append('\n');
        message.append("    expected bus trace (").append(testCase.cycles().size()).append("):\n");
        message.append("        ").append(testCase.cycles().stream()
                .map(c -> String.format("[%04X, %02X, %s]", c.address(), c.value(), c.type()))
                .toList()).append('\n');

        return message.toString();
    }

    private static String registers(
            final String label,
            final int pc,
            final int s,
            final int a,
            final int x,
            final int y,
            final int p
    ) {
        return String.format(
                "%-8s %04X   %02X   %02X   %02X   %02X   %02X", label, pc, s, a, x, y, p
        );
    }
}
