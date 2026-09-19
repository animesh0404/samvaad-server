package com.samvaad.samvaad_server.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the packaged Angular Web Admin SPA shell for client-side routes.
 *
 * The Angular production bundle is staged into the executable JAR by the
 * Gradle packaging tasks (see ADR 0012). Deep links and browser refreshes
 * on SPA routes must return {@code index.html} so the client router takes
 * over; API ({@code /api/**}), WebSocket ({@code /ws}), and static files
 * (dotted names served by the resource handler) are never claimed here.
 *
 * When a new top-level SPA route is added to the Angular router, add it to
 * this mapping and to the matching {@code permitAll} entry in
 * {@link SecurityConfig}.
 */
@Controller
public class WebAdminController {

    private static final String SPA_FORWARD = "forward:/index.html";

    @GetMapping({"/", "/login", "/profile", "/users", "/users/**"})
    public String forwardSpa() {
        return SPA_FORWARD;
    }
}
