package mynes.nestests;

import mynes.cpu.EventListener;

public class NestestCPUListener implements EventListener {
    private NestestLogParser.Entry currentStep = new NestestLogParser.Entry(0, new int[]{}, 0, 0, 0, 0, 0, 0);
    private NestestLogParser.Entry previousStep = new NestestLogParser.Entry(0, new int[]{}, 0, 0, 0, 0, 0, 0);

    @Override
    public void onStep(final int pc,
                       final int a,
                       final int x,
                       final int y,
                       final int p,
                       final int sp,
                       final int[] instruction,
                       final int cycles
    ) {
        previousStep = this.currentStep;

        this.currentStep = new NestestLogParser.Entry(
                pc,
                instruction,
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
