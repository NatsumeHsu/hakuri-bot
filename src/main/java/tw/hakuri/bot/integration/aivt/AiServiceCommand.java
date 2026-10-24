package tw.hakuri.bot.integration.aivt;

import java.nio.file.Path;
import java.util.List;

record AiServiceCommand(String name, List<String> command, Path directory, int port, String logName) { }
