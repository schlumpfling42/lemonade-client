package net.rka.ai.agent;

import net.rka.ai.agent.Ledger.Status;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The job's record of its changes. */
class LedgerTest {

    @Test
    void startsPendingAndFindsTheNextOne() {
        Ledger ledger = new Ledger(java.util.List.of()).resizedTo(3);
        assertEquals(0, ledger.nextPending());

        ledger = ledger.with(0, Status.APPLIED, "").with(1, Status.FAILED, "section gone");
        assertEquals(2, ledger.nextPending());
        assertEquals(Set.of(0), ledger.indexes(Status.APPLIED));
        assertEquals(Set.of(1), ledger.indexes(Status.FAILED));
        assertTrue(ledger.anyApplied());

        assertEquals(-1, ledger.with(2, Status.APPLIED, "").nextPending());
    }

    @Test
    void resizingKeepsWhatIsDoneAndAddsPendingEntries() {
        Ledger ledger = new Ledger(java.util.List.of()).resizedTo(2).with(0, Status.APPLIED, "");

        Ledger longer = ledger.resizedTo(4);
        assertEquals(Status.APPLIED, longer.entries().get(0).status());
        assertEquals(1, longer.nextPending());
        assertEquals(4, longer.entries().size());
        assertEquals(1, ledger.resizedTo(1).entries().size());
    }

    @Test
    void roundTripsThroughJsonAndReadsGarbageAsEmpty() {
        Ledger ledger = new Ledger(java.util.List.of()).resizedTo(2).with(1, Status.FAILED, "why");
        assertEquals(ledger, Ledger.parse(ledger.toJson()));
        assertEquals(0, Ledger.parse("").entries().size());
        assertEquals(0, Ledger.parse("not json").entries().size());
        assertFalse(Ledger.parse("").anyApplied());
    }
}
