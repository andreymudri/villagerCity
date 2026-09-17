package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import javax.annotation.Nullable;

/** The paver's site preparation: levels unprepared plots. A placeholder that plans nothing yet; Task 3 implements it. */
public final class SitePrep implements Job {
    @Override
    public @Nullable Task plan(TaskContext ctx) {
        return null;
    }

    @Override
    public @Nullable String waitingFor() {
        return null;
    }
}
