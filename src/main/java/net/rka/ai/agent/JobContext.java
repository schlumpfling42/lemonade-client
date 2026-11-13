package net.rka.ai.agent;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Puts together the opening system message of one model call of a coding job: only the parts the phase uses ({@link JobPhase}), in a
 * fixed order, and says in one line what was sent, so the size of every part can be read in the agent log:
 * {@code ctx edit: goal 0.1k · folder 0.1k · guide 5.2k · notes 2.9k = 8.4k chars}.
 */
final class JobContext {

    private JobContext() {
    }

    /** The intro that every opening starts with. */
    static final String INTRO = "You are working on a coding job. The next message says which part of the job to do now. You have no tools:"
            + " answer with text only, in exactly the form asked for. Be brief: think in a few words and write no explanations.\n\n";

    /**
     * @param phase the call being made
     * @param parts every part the job has, by kind (a missing or blank part is not sent); the notes are cut to the phase's limit here
     * @return the opening message
     */
    static String compose(JobPhase phase, Map<JobPhase.Part, String> parts) {
        StringBuilder out = new StringBuilder(INTRO);
        for (JobPhase.Part part : JobPhase.Part.values()) {
            String text = sent(phase, parts, part);
            if (!text.isBlank()) out.append(text);
        }
        return out.toString();
    }

    /** What the call sends of one part: "" when the phase does not use it or the job has none. */
    static String sent(JobPhase phase, Map<JobPhase.Part, String> parts, JobPhase.Part part) {
        if (!phase.uses(part)) return "";
        String text = parts.getOrDefault(part, "");
        if (part == JobPhase.Part.NOTES && phase.notesChars() > 0 && text.length() > phase.notesChars()) {
            return text.substring(0, phase.notesChars()) + "\n[... cut ...]";
        }
        return text;
    }

    /** The log line: the size of each part sent, and the total. */
    static String describe(JobPhase phase, Map<JobPhase.Part, String> parts) {
        StringBuilder out = new StringBuilder("   ctx ").append(phase.name().toLowerCase(Locale.ROOT)).append(": ");
        int total = JobContext.INTRO.length();
        Map<JobPhase.Part, Integer> sizes = new EnumMap<>(JobPhase.Part.class);
        for (JobPhase.Part part : JobPhase.Part.values()) {
            int size = sent(phase, parts, part).length();
            if (size > 0) sizes.put(part, size);
            total += size;
        }
        sizes.forEach((part, size) -> out.append(part.name().toLowerCase(Locale.ROOT)).append(' ').append(kilo(size)).append(" · "));
        return out.append("= ").append(kilo(total)).append(" chars (opening)").toString();
    }

    private static String kilo(int chars) {
        return String.format(Locale.ROOT, "%.1fk", chars / 1000.0);
    }
}
