package tw.hakuri.bot.conversation.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AiReplyServiceTest {
    @Test void assemblesCurrentReplyAndReplacesItOnNextTurn() {
        var replies = new AiReplyService(); replies.begin();
        replies.append("你好，", "白璃"); long revision = replies.snapshot().revision();
        replies.append("今天過得好嗎？", "白璃");
        assertThat(replies.snapshot().text()).isEqualTo("你好，今天過得好嗎？");
        assertThat(replies.snapshot().revision()).isGreaterThan(revision);
        replies.complete(); assertThat(replies.snapshot().state()).isEqualTo("COMPLETE");
        replies.begin(); replies.append("新回覆", "白璃"); assertThat(replies.snapshot().text()).isEqualTo("新回覆");
    }
    @Test void interruptedReplyRetainsItsTextUntilTheNextAnswer() {
        var replies = new AiReplyService(); replies.append("部分回覆", ""); replies.interrupt();
        assertThat(replies.snapshot().text()).isEqualTo("部分回覆");
        assertThat(replies.snapshot().state()).isEqualTo("INTERRUPTED");
        replies.begin(); assertThat(replies.snapshot().text()).isEmpty();
    }
    @Test void responseLengthIsBoundedWithoutSplittingUnicodeCharacters() {
        var replies = new AiReplyService(); replies.append("😀".repeat(16001), "白璃");
        assertThat(replies.snapshot().text()).isEqualTo("😀".repeat(16000)); assertThat(replies.snapshot().truncated()).isTrue();
    }
}
