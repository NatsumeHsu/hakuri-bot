package tw.hakuri.bot.integration.aivt.log;

import java.time.Instant;

public record AiLogEntry(long id, Instant receivedAt, String source, String text) { }
