package tw.hakuri.bot.admin.service;

import tw.hakuri.bot.settings.entity.SettingEntity;
import tw.hakuri.bot.settings.repository.SettingRepository;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class AdminCredentialService {
    private final SettingRepository repository;
    public AdminCredentialService(SettingRepository repository) { this.repository = repository; }

    @Transactional
    public String adminPasswordHash() {
        return repository.findById("ADMIN_PASSWORD_HASH").map(SettingEntity::getValue).orElseGet(() -> {
            String password = "admin";
            String hash = "{bcrypt}" + new BCryptPasswordEncoder().encode(password);
            repository.save(new SettingEntity("ADMIN_PASSWORD_HASH", hash));
            LoggerFactory.getLogger(AdminCredentialService.class).info("初次後台登入帳號 admin，初始密碼：{}；登入後請修改密碼", password);
            return hash;
        });
    }

    @Transactional
    public void changePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalArgumentException("密碼至少 12 字元，UTF-8 編碼不可超過 72 位元組");
        repository.saveAndFlush(new SettingEntity("ADMIN_PASSWORD_HASH", "{bcrypt}" + new BCryptPasswordEncoder().encode(password)));
    }
}
