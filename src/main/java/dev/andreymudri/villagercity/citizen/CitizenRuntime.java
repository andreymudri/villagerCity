package dev.andreymudri.villagercity.citizen;

import javax.annotation.Nullable;

/** Transient per-villager scheduler state; never saved. After a load, jobs rebuild tasks by re-planning. */
public final class CitizenRuntime {
    @Nullable Job job;
    @Nullable JobType jobType;
    @Nullable Task current;
    long idleUntil;
    @Nullable Job forcedJob;

    /** Runs this job instead of the one registered for the citizen's JobType (GameTests only). */
    public void forceJob(@Nullable Job job) {
        this.forcedJob = job;
        this.current = null;
        this.idleUntil = 0;
    }

    public @Nullable Task currentTask() {
        return current;
    }

    public @Nullable Job activeJob() {
        return forcedJob != null ? forcedJob : job;
    }
}
