package tw.hakuri.bot.admin.security;

import tw.hakuri.bot.config.BotConfig;
import tw.hakuri.bot.admin.service.AdminCredentialService;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type = org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
public class AdminSecurity {
    @Bean UserDetailsService adminUsers(AdminCredentialService credentials, BotConfig config) {
        credentials.adminPasswordHash();
        return username -> {
            if (!username.equals("admin")) throw new UsernameNotFoundException("帳號不存在");
            return User.withUsername("admin").password(credentials.adminPasswordHash()).roles("ADMIN").build();
        };
    }
    @Bean SecurityFilterChain adminFilterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/ai/reply", "/api/ai/reply/style", "/obs.html", "/obs-reply.js", "/subtitle-text.js").permitAll()
                .requestMatchers("/error", "/login.html", "/login.js", "/admin.css", "/api/csrf").permitAll().anyRequest().hasRole("ADMIN"))
                .formLogin(login -> login.loginPage("/login.html").loginProcessingUrl("/login")
                        .failureUrl("/login.html?error").defaultSuccessUrl("/", true).permitAll())
                .logout(logout -> logout.logoutSuccessUrl("/login.html?logout"))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) -> {
                    if (request.getRequestURI().startsWith("/api/")) response.sendError(401);
                    else response.sendRedirect("/login.html");
                }))
                .build();
    }
}
