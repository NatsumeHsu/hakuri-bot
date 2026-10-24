package tw.hakuri.bot.integration.aivt;

import java.nio.file.*;
import java.util.List;

/** Separate JVM used to prove OS handle cleanup without running Spring shutdown hooks. */
public class JobOwnerProbe {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var group = new WindowsProcessGroup();
        var child = group.start(new AiServiceCommand("test", List.of(WindowsProcessGroupTest.powershell().toString(),
                "-NoProfile", "-NonInteractive", "-Command", "Start-Sleep -Seconds 60"), directory, 0, "halt-child.log"), directory.resolve("halt-child.log"));
        Files.writeString(directory.resolve("halt-child.pid"), Long.toString(child.pid()));
        Runtime.getRuntime().halt(0);
    }
}
