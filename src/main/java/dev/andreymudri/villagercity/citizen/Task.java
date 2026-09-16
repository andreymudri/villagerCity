package dev.andreymudri.villagercity.citizen;

/** One unit of citizen work, ticked by the scheduler until it stops returning RUNNING. */
public interface Task {
    enum Status { RUNNING, SUCCESS, FAILED }

    default void start(TaskContext ctx) {
    }

    Status tick(TaskContext ctx);

    /** Called once when the task ends for any reason other than being paused. */
    default void stop(TaskContext ctx) {
    }
}
