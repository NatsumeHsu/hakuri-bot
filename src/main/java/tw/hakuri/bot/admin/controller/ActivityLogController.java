package tw.hakuri.bot.admin.controller;

import tw.hakuri.bot.observability.activity.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/activity")
public class ActivityLogController {
    private final ActivityLogService activity;
    public ActivityLogController(ActivityLogService activity) { this.activity = activity; }
    @GetMapping public ActivitySnapshot logs(@RequestParam(defaultValue = "all") String source,
                                            @RequestParam(defaultValue = "0") long after,
                                            @RequestParam(defaultValue = "") String instance) {
        return activity.snapshot(source, after, instance);
    }
}
