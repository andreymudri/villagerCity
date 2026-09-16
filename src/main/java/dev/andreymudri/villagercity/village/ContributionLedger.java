package dev.andreymudri.villagercity.village;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Per-player contribution totals for one village. */
public final class ContributionLedger {
    private final Map<UUID, EnumMap<ContributionCategory, Long>> totals = new HashMap<>();

    public void record(UUID player, ContributionCategory category, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("contribution amount must be positive: " + amount);
        }
        totals.computeIfAbsent(player, k -> new EnumMap<>(ContributionCategory.class)).merge(category, amount, Long::sum);
    }

    public long total(UUID player, ContributionCategory category) {
        EnumMap<ContributionCategory, Long> byCategory = totals.get(player);
        return byCategory == null ? 0 : byCategory.getOrDefault(category, 0L);
    }

    public long total(UUID player) {
        EnumMap<ContributionCategory, Long> byCategory = totals.get(player);
        return byCategory == null ? 0 : byCategory.values().stream().mapToLong(Long::longValue).sum();
    }

    /** The player with the highest total; ties go to the smallest UUID so the result is deterministic. */
    public Optional<UUID> topContributor() {
        return totals.keySet().stream()
                .min(Comparator.comparingLong((UUID player) -> -total(player)).thenComparing(Comparator.naturalOrder()));
    }

    /** Deep, immutable copy of the totals. */
    public Map<UUID, Map<ContributionCategory, Long>> snapshot() {
        Map<UUID, Map<ContributionCategory, Long>> copy = new HashMap<>();
        totals.forEach((player, byCategory) -> copy.put(player, Map.copyOf(byCategory)));
        return Map.copyOf(copy);
    }

    public static ContributionLedger fromSnapshot(Map<UUID, Map<ContributionCategory, Long>> snapshot) {
        ContributionLedger ledger = new ContributionLedger();
        snapshot.forEach((player, byCategory) -> byCategory.forEach((category, amount) -> {
            if (amount > 0) {
                ledger.record(player, category, amount);
            }
        }));
        return ledger;
    }
}
