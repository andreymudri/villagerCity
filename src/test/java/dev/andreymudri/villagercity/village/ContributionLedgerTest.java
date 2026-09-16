package dev.andreymudri.villagercity.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContributionLedgerTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @Test
    void recordsAndSumsPerCategory() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.record(A, ContributionCategory.DEPOSIT, 5);
        ledger.record(A, ContributionCategory.DEPOSIT, 7);
        assertEquals(12, ledger.total(A, ContributionCategory.DEPOSIT));
        assertEquals(12, ledger.total(A));
        assertEquals(0, ledger.total(B));
    }

    @Test
    void rejectsNonPositiveAmounts() {
        ContributionLedger ledger = new ContributionLedger();
        assertThrows(IllegalArgumentException.class, () -> ledger.record(A, ContributionCategory.DEPOSIT, 0));
        assertThrows(IllegalArgumentException.class, () -> ledger.record(A, ContributionCategory.DEPOSIT, -3));
    }

    @Test
    void emptyLedgerHasNoTopContributor() {
        assertEquals(Optional.empty(), new ContributionLedger().topContributor());
    }

    @Test
    void topContributorBreaksTiesBySmallestUuid() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.record(B, ContributionCategory.DEPOSIT, 10);
        ledger.record(A, ContributionCategory.DEPOSIT, 10);
        assertEquals(Optional.of(A), ledger.topContributor());
        ledger.record(B, ContributionCategory.DEPOSIT, 1);
        assertEquals(Optional.of(B), ledger.topContributor());
    }

    @Test
    void snapshotRoundTripsAndIsDetached() {
        ContributionLedger ledger = new ContributionLedger();
        ledger.record(A, ContributionCategory.DEPOSIT, 3);
        Map<UUID, Map<ContributionCategory, Long>> snapshot = ledger.snapshot();
        ledger.record(A, ContributionCategory.DEPOSIT, 4);
        assertEquals(3L, snapshot.get(A).get(ContributionCategory.DEPOSIT));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put(B, Map.of()));
        ContributionLedger copy = ContributionLedger.fromSnapshot(snapshot);
        assertEquals(3, copy.total(A, ContributionCategory.DEPOSIT));
        assertTrue(copy.topContributor().isPresent());
    }
}
