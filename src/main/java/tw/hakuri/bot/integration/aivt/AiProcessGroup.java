package tw.hakuri.bot.integration.aivt;

import java.io.IOException;
import java.nio.file.Path;

interface AiProcessGroup extends AutoCloseable {
    interface Child {
        long pid();
        boolean alive();
        int exitCode();
    }
    Child start(AiServiceCommand command, Path log) throws IOException;
    @Override void close();
}
