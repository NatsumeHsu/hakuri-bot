package tw.hakuri.bot.admin.service;

import tw.hakuri.bot.observability.activity.ActivityLogService;

import tw.hakuri.bot.audio.service.AudioService;
import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.conversation.service.ConversationService;
import tw.hakuri.bot.integration.aivt.AivtLauncher;
import tw.hakuri.bot.integration.openllm.ProxyClient;
import tw.hakuri.bot.settings.service.SettingsStore;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.io.IOException;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiActivationServiceTest {
    BotConfig config;
    AivtLauncher launcher;
    AudioService audio;
    SettingsStore settings;
    ProxyClient proxy;
    ConversationService conversation;
    AiActivationService activation;
    @BeforeEach void setup() {
        config = new BotConfig(new MockEnvironment().withProperty("OPENLLM_ENABLED", "false"));
        launcher = mock(AivtLauncher.class); audio = mock(AudioService.class); settings = mock(SettingsStore.class); proxy = mock(ProxyClient.class);
        conversation = new ConversationService(config, proxy, audio, org.mockito.Mockito.mock(ActivityLogService.class), new tw.hakuri.bot.conversation.service.AiReplyService());
        activation = new AiActivationService(launcher, audio, config, settings, conversation, mock(ActivityLogService.class));
    }
    @AfterEach void cleanup() { conversation.stop(); }
    @Test void connectsAfterServicesAreReadyOnlyWhenSelectedAndPreservesPreferences() throws Exception {
        when(settings.read()).thenReturn(Map.of("OPENLLM_ENABLED", "true", "DISCORD_VOICE_ENABLED", "true"));
        conversation.start(); verifyNoInteractions(proxy);
        activation.start();
        assertThat(config.openllmEnabled()).isFalse();
        verifyNoInteractions(proxy);
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class);
        verify(launcher).start(callback.capture());
        callback.getValue().run();
        assertThat(config.openllmEnabled()).isTrue();
        assertThat(config.discordVoiceEnabled()).isTrue();
        verify(audio).checkFfmpeg(); verify(settings, never()).save(anyMap());
        conversation.start(); conversation.start();
        verify(proxy, times(1)).start(any(), any(), any());
    }
    @Test void leavesAiDisconnectedWhenConnectionSettingIsOff() {
        when(settings.read()).thenReturn(Map.of("OPENLLM_ENABLED", "false", "DISCORD_VOICE_ENABLED", "false"));
        activation.start();
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class); verify(launcher).start(callback.capture());
        callback.getValue().run();
        assertThat(config.openllmEnabled()).isFalse(); assertThat(config.discordVoiceEnabled()).isFalse();
        verifyNoInteractions(proxy); verify(settings, never()).save(anyMap());
    }
    @Test void voiceRemainsDisabledWhenOnlyOpenLlmIsSelected() {
        when(settings.read()).thenReturn(Map.of("OPENLLM_ENABLED", "true", "DISCORD_VOICE_ENABLED", "false"));
        activation.start();
        var callback = org.mockito.ArgumentCaptor.forClass(Runnable.class); verify(launcher).start(callback.capture());
        callback.getValue().run();
        assertThat(config.openllmEnabled()).isTrue(); assertThat(config.discordVoiceEnabled()).isFalse();
        verify(proxy).start(any(), any(), any());
    }
    @Test void failedFfmpegDoesNotLaunchOrEnableAi() throws Exception {
        doThrow(new IOException("missing")).when(audio).checkFfmpeg();
        assertThatThrownBy(activation::start).isInstanceOf(IllegalStateException.class);
        assertThat(config.openllmEnabled()).isFalse(); verifyNoInteractions(launcher, settings, proxy);
    }
    @Test void failedScriptDoesNotChangeAiSettings() {
        when(launcher.start(any(Runnable.class))).thenThrow(new IllegalStateException("script missing"));
        assertThatThrownBy(activation::start).isInstanceOf(IllegalStateException.class);
        assertThat(config.openllmEnabled()).isFalse(); verifyNoInteractions(settings, proxy);
    }
}
