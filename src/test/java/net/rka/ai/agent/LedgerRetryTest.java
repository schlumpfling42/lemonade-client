package net.rka.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failed change is tried once more after others were applied, never a third time. */
class LedgerRetryTest {

    private static Ledger ledger(Ledger.Status... statuses) {
        return new Ledger(java.util.Arrays.stream(statuses).map(s -> new Ledger.Entry(s, s == Ledger.Status.FAILED ? "no import" : "")).toList());
    }

    @Test
    void aFailedChangeGoesBackToPendingWhenSomethingElseWasApplied() {
        Ledger again = CodingGraph.retryFailed(ledger(Ledger.Status.FAILED, Ledger.Status.APPLIED));

        assertEquals(Ledger.Status.PENDING, again.entries().get(0).status());
        assertTrue(again.entries().get(0).note().startsWith(CodingGraph.SECOND_TRY));
        assertEquals(0, again.nextPending());
    }

    @Test
    void nothingIsRetriedWhenNothingWasApplied() {
        Ledger only = ledger(Ledger.Status.FAILED, Ledger.Status.FAILED);
        assertSame(only, CodingGraph.retryFailed(only));
    }

    @Test
    void aChangeThatFailedItsSecondTryIsLeftFailed() {
        Ledger second = new Ledger(List.of(new Ledger.Entry(Ledger.Status.FAILED, CodingGraph.SECOND_TRY + "still no"),
                new Ledger.Entry(Ledger.Status.APPLIED, "")));
        assertSame(second, CodingGraph.retryFailed(second));
    }
}
