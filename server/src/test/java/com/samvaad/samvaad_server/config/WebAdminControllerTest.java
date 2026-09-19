package com.samvaad.samvaad_server.config;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * Covers the SPA fallback without requiring an Angular build: the packaged
 * {@code index.html} is a build-time artifact, so these tests assert the
 * forward decision only. API, WebSocket, and static-file paths must never
 * be claimed by the fallback.
 */
class WebAdminControllerTest {

    private final MockMvc mockMvc = standaloneSetup(new WebAdminController()).build();

    @Test
    void forwardsKnownSpaRoutesToIndex() throws Exception {
        for (String path : new String[]{"/", "/login", "/profile", "/users", "/users/new", "/users/some-id"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void doesNotClaimApiWebSocketOrStaticPaths() throws Exception {
        for (String path : new String[]{"/api/users", "/api/auth/login", "/ws", "/main-abc123.js", "/logo.png"}) {
            mockMvc.perform(get(path))
                    .andExpect(status().isNotFound());
        }
    }
}
