package com.claudoc.agent;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * In-memory ring buffer that records recent UI actions (API-level).
 * Used by MemoryManager to inject UI context into the system prompt.
 */
@Component
public class UiActionTracker {

    private static final int MAX_ACTIONS = 10;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    private final LinkedList<UiAction> actions = new LinkedList<>();

    public record UiAction(String type, String detail, String time) {}

    public synchronized void record(String type, String detail) {
        actions.addLast(new UiAction(type, detail, LocalDateTime.now().format(TIME_FMT)));
        if (actions.size() > MAX_ACTIONS) {
            actions.removeFirst();
        }
    }

    public synchronized List<UiAction> getRecent() {
        return new ArrayList<>(actions);
    }

    /** Format recent actions as a readable string for system prompt injection. */
    public synchronized String format() {
        if (actions.isEmpty()) return "";

        StringBuilder sb = new StringBuilder("## Recent User Actions\n");
        for (UiAction a : actions) {
            sb.append(String.format("- [%s] %s", a.time(), a.type()));
            if (a.detail() != null && !a.detail().isEmpty()) {
                sb.append(": ").append(a.detail());
            }
            sb.append("\n");
        }
        return sb.toString();
    }
}
