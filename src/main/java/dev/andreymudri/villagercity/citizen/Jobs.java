package dev.andreymudri.villagercity.citizen;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/** JobType to Job factory. Filled once during mod construction. */
public final class Jobs {
    private static final Map<JobType, Supplier<Job>> FACTORIES = new EnumMap<>(JobType.class);

    private Jobs() {
    }

    public static synchronized void register(JobType type, Supplier<Job> factory) {
        FACTORIES.put(type, factory);
    }

    public static synchronized @Nullable Job create(JobType type) {
        Supplier<Job> factory = FACTORIES.get(type);
        return factory == null ? null : factory.get();
    }
}
