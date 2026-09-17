package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import javax.annotation.Nullable;

/** Prepares unprepared plots first ({@link SitePrep}), then lays queued paths to the bell ({@link PathWork}). */
public final class PaverJob implements Job {
    private final SitePrep prep = new SitePrep();
    private final PathWork paths = new PathWork();
    private @Nullable Job last;
    private @Nullable String waitingFor;

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        waitingFor = null;
        Task task = prep.plan(ctx);
        last = prep;
        if (task == null) {
            task = paths.plan(ctx);
            last = paths;
        }
        if (task == null) {
            waitingFor = prep.waitingFor() != null ? prep.waitingFor() : paths.waitingFor() != null ? paths.waitingFor() : "a plot to prepare or a path to lay";
        }
        return task;
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        if (last != null) {
            last.onTaskFinished(ctx, task, status);
        }
    }

    @Override
    public @Nullable String waitingFor() {
        return waitingFor;
    }
}
