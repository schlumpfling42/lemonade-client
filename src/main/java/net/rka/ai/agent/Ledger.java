package net.rka.ai.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The job's record of its changes: one entry per change of the approved plan, in plan order, each pending, applied or
 * failed (with a note). It is data in the graph state, not a conversation: the edit step works on the first pending
 * entry, the plan dialog marks applied and failed changes, the report and the next edit's "what was already done"
 * are made from it, so nothing is reconstructed and a revised plan keeps what is done.
 *
 * @param entries one per change
 */
record Ledger(List<Entry> entries) {

    enum Status { PENDING, APPLIED, FAILED }

    /** @param note what happened, for the report ("section not found"); empty while pending or when it simply worked */
    record Entry(Status status, String note) {
        Entry {
            status = status == null ? Status.PENDING : status;
            note = note == null ? "" : note;
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    Ledger {
        entries = List.copyOf(entries);
    }

    /** From the state's JSON; an empty or unreadable text is an empty ledger. */
    static Ledger parse(String json) {
        if (json == null || json.isBlank()) return new Ledger(List.of());
        try {
            return new Ledger(JSON.readValue(json, new TypeReference<List<Entry>>() {
            }));
        } catch (JsonProcessingException e) {
            return new Ledger(List.of());
        }
    }

    String toJson() {
        try {
            return JSON.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e); // a list of enums and strings always serializes
        }
    }

    /** The same ledger for a plan of {@code changes} changes: entries kept by index, new ones pending, extra ones dropped. */
    Ledger resizedTo(int changes) {
        List<Entry> resized = new ArrayList<>();
        for (int i = 0; i < changes; i++) resized.add(i < entries.size() ? entries.get(i) : new Entry(Status.PENDING, ""));
        return new Ledger(resized);
    }

    Ledger with(int index, Status status, String note) {
        List<Entry> changed = new ArrayList<>(entries);
        changed.set(index, new Entry(status, note));
        return new Ledger(changed);
    }

    /** The index of the first pending entry, or -1 when every change is done or failed. */
    int nextPending() {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).status() == Status.PENDING) return i;
        return -1;
    }

    Set<Integer> indexes(Status status) {
        Set<Integer> indexes = new TreeSet<>();
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).status() == status) indexes.add(i);
        return indexes;
    }

    boolean anyApplied() {
        return !indexes(Status.APPLIED).isEmpty();
    }
}
