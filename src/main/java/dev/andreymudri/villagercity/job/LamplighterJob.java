package dev.andreymudri.villagercity.job;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import javax.annotation.Nullable;

/** Lights dark village ground. A placeholder that plans nothing yet; Task 5 implements it. */
public final class LamplighterJob implements Job {
    @Override
    public @Nullable Task plan(TaskContext ctx) {
        return null;
    }

    @Override
    public @Nullable String waitingFor() {
        return "nothing yet";
    }
}
