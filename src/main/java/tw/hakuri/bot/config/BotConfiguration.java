package tw.hakuri.bot.config;

import tw.hakuri.bot.settings.service.SettingsStore;

import org.springframework.context.annotation.*;
import java.io.IOException;

@Configuration
public class BotConfiguration {
    @Bean BotConfig botConfig(SettingsStore store) throws IOException {
        store.migrateLegacy();
        return new BotConfig(store.resolvedEnvironment());
    }
}
