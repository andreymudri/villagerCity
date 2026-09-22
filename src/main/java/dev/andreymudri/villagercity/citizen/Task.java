package dev.andreymudri.villagercity.citizen;

/** One unit of citizen work, ticked by the scheduler until it stops returning RUNNING. */
public interface Task {
    enum Status { RUNNING, SUCCESS, FAILED }

    default void start(TaskContext ctx) {
    }

    Status tick(TaskContext ctx);

    /**
     * Called once when the task ends for any reason other than being paused, including the villager leaving the level
     * (death, discard, chunk unload, dimension change). {@code ctx.village()} is null when the task is stopped because
     * the citizen's village no longer exists or could not be looked up.
     */
    default void stop(TaskContext ctx) {
    }

    /** What the task is doing, for /villagercity village. */
    default String describe(TaskContext ctx) {
        return getClass().getSimpleName();
    }
}
