package tw.hakuri.bot.admin.controller;

import tw.hakuri.bot.integration.aivt.log.AiLogService;
import tw.hakuri.bot.integration.aivt.log.AiLogSnapshot;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai/logs")
public class AiLogController {
    private final AiLogService logs;
    public AiLogController(AiLogService logs) { this.logs = logs; }
    @GetMapping public AiLogSnapshot logs(@RequestParam(defaultValue = "all") String source,
                                          @RequestParam(defaultValue = "0") long after,
                                          @RequestParam(defaultValue = "") String instance) {
        return logs.snapshot(source, after, instance);
    }
}
