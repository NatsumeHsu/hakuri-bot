package tw.hakuri.bot;

import tw.hakuri.bot.config.SettingsKeys;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class HakuriBotApplication {
    public static void main(String[] args) {
        Dotenv.configure().ignoreIfMissing().load().entries().forEach(entry -> {
            if (SettingsKeys.ENV_KEYS.contains(entry.getKey()) && System.getenv(entry.getKey()) == null && System.getProperty(entry.getKey()) == null)
                System.setProperty(entry.getKey(), entry.getValue());
        });
        SpringApplication.run(HakuriBotApplication.class, args);
    }
}
// http://127.0.0.1:8080