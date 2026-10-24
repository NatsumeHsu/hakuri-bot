package tw.hakuri.bot.settings.repository;

import tw.hakuri.bot.settings.entity.SettingEntity;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SettingRepository extends JpaRepository<SettingEntity, String> { }
