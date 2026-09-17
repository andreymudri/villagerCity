package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import javax.annotation.Nullable;

/** Crafts and smelts what the other jobs need. A placeholder that plans nothing yet; Task 7 implements it. */
public final class ArtisanJob implements Job {
    @Override
    public @Nullable Task plan(TaskContext ctx) {
        return null;
    }

    @Override
    public @Nullable String waitingFor() {
        return "nothing yet";
    }
}
