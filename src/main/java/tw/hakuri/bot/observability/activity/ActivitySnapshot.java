package tw.hakuri.bot.observability.activity;

import java.util.List;

public record ActivitySnapshot(String instanceId, long cursor, boolean reset, boolean truncated, boolean hasMore,
                               int capacity, String currentStep, List<ActivityEntry> entries) { }
