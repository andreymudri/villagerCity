package dev.andreymudri.villagercity.citizen;

import java.util.List;

/** Runs tasks in order; fails as soon as one fails. */
public final class TaskSequence implements Task {
    private final List<Task> tasks;
    private int index;
    private boolean failed;

    public TaskSequence(List<Task> tasks) {
        this.tasks = List.copyOf(tasks);
    }

    public static TaskSequence of(Task... tasks) {
        return new TaskSequence(List.of(tasks));
    }

    @Override
    public void start(TaskContext ctx) {
        index = 0;
        failed = false;
        if (!tasks.isEmpty()) {
            tasks.get(0).start(ctx);
        }
    }

    @Override
    public Status tick(TaskContext ctx) {
        if (failed) {
            return Status.FAILED;
        }
        if (index >= tasks.size()) {
            return Status.SUCCESS;
        }
        Task current = tasks.get(index);
        Status status = current.tick(ctx);
        if (status == Status.RUNNING) {
            return Status.RUNNING;
        }
        current.stop(ctx);
        if (status == Status.FAILED) {
            failed = true;
            return Status.FAILED;
        }
        index++;
        if (index >= tasks.size()) {
            return Status.SUCCESS;
        }
        tasks.get(index).start(ctx);
        return Status.RUNNING;
    }

    @Override
    public void stop(TaskContext ctx) {
        if (!failed && index < tasks.size()) {
            tasks.get(index).stop(ctx);
        }
    }

    @Override
    public String describe(TaskContext ctx) {
        return tasks.isEmpty() ? "nothing" : currentStep().describe(ctx);
    }

    /** The running step, the one that failed, or the last one once all succeeded. */
    public Task currentStep() {
        return tasks.get(Math.min(index, tasks.size() - 1));
    }
}
