package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import javax.annotation.Nullable;

/** The paver's path work: lays queued paths from new houses to the bell. A placeholder that plans nothing yet; Task 4 implements it. */
public final class PathWork implements Job {
    @Override
    public @Nullable Task plan(TaskContext ctx) {
        return null;
    }

    @Override
    public @Nullable String waitingFor() {
        return null;
    }
}
