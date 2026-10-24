package tw.hakuri.bot.admin.controller;

import tw.hakuri.bot.admin.dto.PasswordInput;
import tw.hakuri.bot.admin.dto.TextInput;
import tw.hakuri.bot.admin.service.AdminService;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AdminController {
    private final AdminService admin;
    public AdminController(AdminService admin) { this.admin = admin; }

    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) {
        return Map.of("header", token.getHeaderName(), "token", token.getToken());
    }
    @GetMapping("/status") public Map<String, Object> status() { return admin.status(); }
    @GetMapping("/settings") public Map<String, Object> settings() { return admin.settings(); }
    @PutMapping("/settings") public Map<String, Object> save(@RequestBody Map<String, String> values) { return admin.saveSettings(values); }
    @PutMapping("/password") public Map<String, String> password(@RequestBody PasswordInput input) { return admin.changePassword(input.password()); }
    @PostMapping("/messages") public Map<String, String> send(@RequestBody TextInput input) { return admin.send(input.text()); }
    @PostMapping("/actions/{action}") public Map<String, String> action(@PathVariable String action) { return admin.action(action); }
}
