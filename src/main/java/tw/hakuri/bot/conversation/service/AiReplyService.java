package tw.hakuri.bot.conversation.service;

import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;

/** Latest reply only: sentences accumulate within one turn and the next turn replaces them. */
@Service
public class AiReplyService {
    public record Reply(String instance, long revision, String text, String name, String state, Instant updatedAt, boolean truncated) { }
    private final String instance = UUID.randomUUID().toString();
    private String text = "", name = "AI", state = "WAITING";
    private long revision;
    private Instant updatedAt = Instant.now();
    private boolean truncated;
    public synchronized Reply snapshot() { return new Reply(instance, revision, text, name, state, updatedAt, truncated); }
    public synchronized void begin() { text = ""; name = "AI"; truncated = false; state = "THINKING"; changed(); }
    public synchronized void append(String sentence, String speaker) {
        if (sentence == null || sentence.isBlank()) return;
        if (!state.equals("THINKING") && !state.equals("STREAMING")) begin();
        String next = text + sentence;
        if (next.codePointCount(0, next.length()) > 16000) { next = next.substring(0, next.offsetByCodePoints(0, 16000)); truncated = true; }
        text = next;
        if (speaker != null && !speaker.isBlank()) name = speaker.substring(0, Math.min(100, speaker.length()));
        state = "STREAMING"; changed();
    }
    public synchronized void complete() { state = "COMPLETE"; changed(); }
    public synchronized void interrupt() { state = "INTERRUPTED"; changed(); }
    public synchronized void error() { state = "ERROR"; changed(); }
    private void changed() { revision++; updatedAt = Instant.now(); }
}
