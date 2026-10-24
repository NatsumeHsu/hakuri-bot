package tw.hakuri.bot.integration.aivt.log;

import java.util.List;

public record AiLogSnapshot(String instanceId, long cursor, boolean reset, boolean truncated,
                            boolean hasMore, int capacity, List<AiLogEntry> entries) { }
