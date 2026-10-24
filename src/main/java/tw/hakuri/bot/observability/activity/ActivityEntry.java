package tw.hakuri.bot.observability.activity;

import java.time.Instant;

public record ActivityEntry(long id, Instant receivedAt, String source, String text) { }
