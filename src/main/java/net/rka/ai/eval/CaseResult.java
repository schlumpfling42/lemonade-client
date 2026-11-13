package net.rka.ai.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The outcome of one case: whether every check passed, how long the run took, what the agent answered, the checks with their details and
 * the metrics read from the run's log.
 *
 * @param error     why the run did not finish ("" when it did); a case with an error has failed
 * @param jobReport the report of the coding job, when one ran
 * @param approvals what the auto-approver was asked
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CaseResult(String id, String description, boolean passed, double seconds, boolean timedOut, String error, String answer,
                         String jobReport, List<EvalChecks.Result> checks, EvalMetrics metrics, List<String> approvals) {

    public int checksPassed() {
        return (int) checks.stream().filter(EvalChecks.Result::ok).count();
    }
}
