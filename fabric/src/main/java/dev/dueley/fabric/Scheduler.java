package dev.dueley.fabric;

import java.util.ArrayList;
import java.util.List;

/**
 * Main-thread tasks in server ticks, like the Bukkit scheduler the plugin uses. It is driven from the start of every
 * server tick, which keeps running while the server is tick-frozen (only the worlds freeze), just like Bukkit's.
 */
public final class Scheduler {
    private final List<Task> tasks = new ArrayList<>();
    private long now;

    public final class Task {
        private long due;
        private final long period;
        private final Runnable runnable;
        private boolean cancelled;

        private Task(long due, long period, Runnable runnable) {
            this.due = due;
            this.period = period;
            this.runnable = runnable;
        }

        public void cancel() {
            cancelled = true;
        }
    }

    /** Run on the next tick (Bukkit runTask). */
    public Task run(Runnable r) {
        return runLater(1, r);
    }

    /** Bukkit runTaskLater: a delay below 1 still waits for the next tick. */
    public Task runLater(long delayTicks, Runnable r) {
        Task t = new Task(now + Math.max(1, delayTicks), 0, r);
        tasks.add(t);
        return t;
    }

    /** Bukkit runTaskTimer. */
    public Task runTimer(long delayTicks, long periodTicks, Runnable r) {
        Task t = new Task(now + Math.max(1, delayTicks), Math.max(1, periodTicks), r);
        tasks.add(t);
        return t;
    }

    void tick() {
        now++;
        for (Task t : new ArrayList<>(tasks)) {
            if (t.cancelled) {
                tasks.remove(t);
                continue;
            }
            if (t.due > now) {
                continue;
            }
            try {
                t.runnable.run();
            } catch (RuntimeException e) {
                DueleyMod.LOGGER.error("Task failed", e);
            }
            if (t.period > 0 && !t.cancelled) {
                t.due = now + t.period;
            } else {
                tasks.remove(t);
            }
        }
    }

    void clear() {
        tasks.clear();
    }
}
