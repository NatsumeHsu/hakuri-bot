package tw.hakuri.bot.admin.controller;

import tw.hakuri.bot.conversation.service.AiReplyService;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/ai/reply")
public class AiReplyController {
    private final AiReplyService replies;
    private final tw.hakuri.bot.admin.service.ObsSettingsService obs;
 public AiReplyController(AiReplyService replies, tw.hakuri.bot.admin.service.ObsSettingsService obs) { this.replies=replies; this.obs=obs; }
 @GetMapping("/style") public ResponseEntity<java.util.Map<String,String>> style() {
 return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(obs.style());
 }
    @GetMapping public ResponseEntity<AiReplyService.Reply> latest() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(replies.snapshot());
    }
}
