package tw.hakuri.bot.integration.discord;

import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.audio.service.AudioService;
import tw.hakuri.bot.conversation.service.ConversationService;
import tw.hakuri.bot.integration.twitch.TwitchClient;
import tw.hakuri.bot.observability.activity.ActivityLogService;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.audio.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscordBotVoiceTest {
    MessageReceivedEvent joinEvent() {
        var event = mock(MessageReceivedEvent.class, RETURNS_DEEP_STUBS);
        when(event.isFromGuild()).thenReturn(true); when(event.getMessage().getContentRaw()).thenReturn("!join");
        return event;
    }
    DiscordBot bot(BotConfig config, ConversationService conversation) {
        return new DiscordBot(config, conversation, mock(AudioService.class), mock(TwitchClient.class), mock(ActivityLogService.class));
    }
    @Test void refusesJoinWhenVoiceSettingIsDisabled() {
        var config = new BotConfig(new MockEnvironment().withProperty("OPENLLM_ENABLED", "true"));
        var conversation = mock(ConversationService.class); var bot = bot(config, conversation); var event = joinEvent();
        try {
            bot.onMessageReceived(event);
            verify(event.getMessage()).reply(contains("Discord 語音已停用"));
            verifyNoInteractions(conversation);
        } finally { bot.stop(); }
    }
    @Test void refusesJoinBeforeAiIsReadyEvenWhenVoiceIsEnabled() {
        var config = new BotConfig(new MockEnvironment().withProperty("DISCORD_VOICE_ENABLED", "true"));
        var conversation = mock(ConversationService.class); var bot = bot(config, conversation); var event = joinEvent();
        try {
            bot.onMessageReceived(event);
            verify(event.getMessage()).reply(contains("AI 尚未就緒"));
            verifyNoInteractions(conversation);
        } finally { bot.stop(); }
    }
    @Test void receiveAndPlaybackHandlersBothRespectVoiceSetting() {
        var config = new BotConfig(new MockEnvironment().withProperty("DISCORD_VOICE_ENABLED", "true").withProperty("OPENLLM_ENABLED", "true"));
        var conversation = mock(ConversationService.class); when(conversation.canProvide()).thenReturn(true);
        var bot = bot(config, conversation); var event = joinEvent();
        try {
            bot.onMessageReceived(event);
            var receiver = org.mockito.ArgumentCaptor.forClass(AudioReceiveHandler.class);
            var sender = org.mockito.ArgumentCaptor.forClass(AudioSendHandler.class);
            verify(event.getGuild().getAudioManager()).setReceivingHandler(receiver.capture());
            verify(event.getGuild().getAudioManager()).setSendingHandler(sender.capture());
            assertThat(receiver.getValue().canReceiveUser()).isTrue(); assertThat(sender.getValue().canProvide()).isTrue();
            config.configureDiscordVoice(false);
            assertThat(receiver.getValue().canReceiveUser()).isFalse(); assertThat(sender.getValue().canProvide()).isFalse();
        } finally { bot.stop(); }
    }
}
