package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * The paver's order of work: an unprepared plot to prepare ({@link SitePrep}), else one street run to grow the
 * village's street graph ({@link StreetWork}), else it waits for {@code "room to grow"}. While a plot it is preparing
 * waits on something (fill material, say), the paver waits with it rather than growing the streets.
 */
public final class PaverJob implements Job {
    private final SitePrep prep = new SitePrep();
    private final StreetWork streets = new StreetWork();
    private @Nullable Job last;
    private @Nullable String waitingFor;

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        waitingFor = null;
        Task task = prep.plan(ctx);
        last = prep;
        if (task != null) {
            return task;
        }
        if (prep.waitingFor() != null) {
            waitingFor = prep.waitingFor();
            return null;
        }
        task = streets.plan(ctx);
        if (task == null && streets.waitingFor() == null) {
            // The run just finished; start the next one now rather than idling a retry first.
            task = streets.plan(ctx);
        }
        last = streets;
        if (task == null) {
            waitingFor = streets.waitingFor() != null ? streets.waitingFor() : "room to grow";
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

    /** The columns this paver's street work gave up on ({@link StreetWork#refused}); never saved. */
    public Set<Long> refusedColumns() {
        return streets.refused();
    }
}
