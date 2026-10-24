package tw.hakuri.bot;

import tw.hakuri.bot.admin.service.AdminCredentialService;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"BOT_ENABLED=false", "spring.main.keep-alive=false", "debug=false",
                "spring.datasource.url=jdbc:sqlite:file:application-test?mode=memory&cache=shared", "hakuri.settings.migrate-legacy=false",
                "hakuri.activity.file=target/test-activity.jsonl"})
@AutoConfigureMockMvc
class ApplicationTest {
    @Autowired MockMvc mvc;
    @Autowired AdminCredentialService credentials;
    @org.junit.jupiter.api.BeforeEach void setTestPassword() { credentials.changePassword("test-only-password"); }
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Test void loadsSpringContextWithoutExternalConnections() { }

    @Test void loadsDiscordDaveNativeLibrary() {
        org.assertj.core.api.Assertions.assertThat(
                club.minnced.discord.jdave.ffi.LibDave.getMaxSupportedProtocolVersion()).isPositive();
    }
    @Test void blocksUnauthenticatedApiAndRedirectsDashboardToLogin() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/logs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/activity")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/ai/reply")).andExpect(status().isOk()).andExpect(jsonPath("$.text").isString());
        mvc.perform(get("/obs.html")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/obs-reply.js")).andExpect(status().isOk());
        mvc.perform(get("/login.html")).andExpect(status().isOk());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk());
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login.html"));
    }
    @Test @WithMockUser(roles = "ADMIN") void servesRealStatusAndDashboard() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.discord.enabled").value(false))
                .andExpect(jsonPath("$.conversation.inputCount").value(0));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/html"));
        mvc.perform(get("/api/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.effective.DISCORD_TOKEN").doesNotExist())
                .andExpect(jsonPath("$.effective.ADMIN_PASSWORD").doesNotExist());
        mvc.perform(get("/api/csrf")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(get("/api/ai/logs")).andExpect(status().isOk()).andExpect(jsonPath("$.capacity").value(5000))
                .andExpect(jsonPath("$.instanceId").isNotEmpty()).andExpect(jsonPath("$.entries").isArray());
        mvc.perform(get("/api/ai/logs").param("source", "../.env")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ai/logs").param("after", "-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/activity")).andExpect(status().isOk()).andExpect(jsonPath("$.currentStep").isString());
        mvc.perform(get("/api/activity").param("source", "voice")).andExpect(status().isOk());
        mvc.perform(get("/api/activity").param("source", "ollama")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/ai/reply")).andExpect(status().isOk()).andExpect(jsonPath("$.text").isString())
                .andExpect(jsonPath("$.revision").isNumber()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test @WithMockUser(roles = "ADMIN") void requiresCsrfForActionsAndValidatesRequests() throws Exception {
        mvc.perform(post("/api/actions/clear")).andExpect(status().isForbidden());
        mvc.perform(post("/api/actions/start-aivt")).andExpect(status().isForbidden());
        mvc.perform(post("/api/actions/clear").with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/messages").with(csrf()).contentType("application/json").content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/settings").with(csrf()).contentType("application/json").content("{\"DISCORD_TOKEN\":\"nope\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test @WithMockUser(roles = "USER") void rejectsNonAdminAccounts() throws Exception {
        mvc.perform(get("/api/status")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/logs")).andExpect(status().isForbidden());
        mvc.perform(get("/api/activity")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ai/reply")).andExpect(status().isOk());
        mvc.perform(post("/api/actions/start-aivt").with(csrf())).andExpect(status().isForbidden());
    }
    @Test void realHttpLoginDashboardAndControlAction() throws Exception {
        var cookies = new java.net.CookieManager(); cookies.setCookiePolicy(java.net.CookiePolicy.ACCEPT_ALL);
        try (var client = java.net.http.HttpClient.newBuilder().cookieHandler(cookies).build()) {
            String base = "http://127.0.0.1:" + port;
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var unauthenticated = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/status")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            org.assertj.core.api.Assertions.assertThat(unauthenticated.statusCode()).isEqualTo(401);
            var csrfResponse = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/csrf")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            String token = json.readTree(csrfResponse.body()).path("token").asText();
            String form = "username=admin&password=test-only-password&_csrf="
                    + java.net.URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8);
            var login = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/login"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form)).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            org.assertj.core.api.Assertions.assertThat(login.statusCode()).isEqualTo(302);
            org.assertj.core.api.Assertions.assertThat(login.headers().firstValue("Location").orElse("")).endsWith("/");
            var dashboard = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            org.assertj.core.api.Assertions.assertThat(dashboard.statusCode()).isEqualTo(200);
            org.assertj.core.api.Assertions.assertThat(dashboard.body()).contains("id=\"overview\"");
            var fresh = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/csrf")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            var csrf = json.readTree(fresh.body());
            var action = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/api/actions/clear"))
                    .header(csrf.path("header").asText(), csrf.path("token").asText())
                    .POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            org.assertj.core.api.Assertions.assertThat(action.statusCode()).isEqualTo(200);
        }
    }
}
