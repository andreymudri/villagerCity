package dev.andreymudri.villagercity.gametest;

import dev.andreymudri.villagercity.citizen.Job;
import dev.andreymudri.villagercity.citizen.Task;
import dev.andreymudri.villagercity.citizen.TaskContext;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.annotation.Nullable;

/** A job that hands out a fixed list of tasks once each and records how they finished. */
public final class ScriptedJob implements Job {
    private final Deque<Task> tasks;
    public final List<Task.Status> results = new CopyOnWriteArrayList<>();
    public final List<Long> finishTimes = new CopyOnWriteArrayList<>();

    public ScriptedJob(Task... tasks) {
        this.tasks = new ArrayDeque<>(Arrays.asList(tasks));
    }

    @Override
    public @Nullable Task plan(TaskContext ctx) {
        return tasks.poll();
    }

    @Override
    public void onTaskFinished(TaskContext ctx, Task task, Task.Status status) {
        results.add(status);
        finishTimes.add(ctx.gameTime());
    }
}
