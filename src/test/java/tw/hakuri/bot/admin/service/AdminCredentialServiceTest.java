package tw.hakuri.bot.admin.service;

import tw.hakuri.bot.settings.entity.SettingEntity;
import tw.hakuri.bot.settings.repository.SettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminCredentialServiceTest {
    @Test void createsHashedAdminDefaultForNewDatabase() {
        var repository = mock(SettingRepository.class);
        when(repository.findById("ADMIN_PASSWORD_HASH")).thenReturn(Optional.empty());
        String hash = new AdminCredentialService(repository).adminPasswordHash();
        assertThat(hash).startsWith("{bcrypt}");
        assertThat(new BCryptPasswordEncoder().matches("admin", hash.substring(8))).isTrue();
        verify(repository).save(argThat(row -> row.getKey().equals("ADMIN_PASSWORD_HASH") && row.getValue().equals(hash)));
    }
    @Test void preservesExistingPassword() {
        var repository = mock(SettingRepository.class);
        when(repository.findById("ADMIN_PASSWORD_HASH")).thenReturn(Optional.of(new SettingEntity("ADMIN_PASSWORD_HASH", "existing-hash")));
        assertThat(new AdminCredentialService(repository).adminPasswordHash()).isEqualTo("existing-hash");
        verify(repository, never()).save(any());
    }
}
