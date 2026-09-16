package dev.andreymudri.villagercity.citizen;

import javax.annotation.Nullable;

/** Decides a citizen's next task. Jobs keep no state that must survive a save; they re-plan from world and village state. */
public interface Job {
    /** The next task to run, or null when there is nothing to do right now (the scheduler retries later). */
    @Nullable
    Task plan(TaskContext ctx);

    default void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
    }
}
