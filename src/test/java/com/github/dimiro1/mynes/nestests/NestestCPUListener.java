package com.github.dimiro1.mynes.nestests;

import com.github.dimiro1.mynes.cpu.EventListener;

public class NestestCPUListener implements EventListener {
    private NestestLogParser.Entry currentStep = new NestestLogParser.Entry(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    private NestestLogParser.Entry previousStep = new NestestLogParser.Entry(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

    @Override
    public void onStep(final int pc,
                       final int a,
                       final int x,
                       final int y,
                       final int p,
                       final int sp,
                       final int opcode,
                       final int operand1,
                       final int operand2,
                       final int opcodeLength,
                       final long cycles
    ) {
        previousStep = this.currentStep;

        this.currentStep = new NestestLogParser.Entry(
                pc,
                opcode,
                operand1,
                operand2,
                opcodeLength,
                a,
                x,
                y,
                p,
                sp,
                cycles
        );
    }

    public NestestLogParser.Entry getCurrentStep() {
        return currentStep;
    }

    public NestestLogParser.Entry getPreviousStep() {
        return previousStep;
    }
}
