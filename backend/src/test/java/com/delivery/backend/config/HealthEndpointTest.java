package com.delivery.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the liveness endpoint, and -- more importantly -- pins the fact that it
 * is the <i>only</i> thing that became public.
 *
 * <p>The endpoint exists because the application had no public 2xx path: an
 * unauthenticated GET answered 403, 404 or 405 on every route, so a hosting
 * platform defining "healthy = 2xx" would never route traffic to a service that
 * was in fact serving perfectly.
 *
 * <p>Widening the security matcher from {@code /api/v1/health} to
 * {@code /api/v1/**} would silently unauthenticate the entire API, and nothing
 * about the health endpoint would look wrong. That is the failure this second
 * test exists to catch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class HealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("liveness answers 200 to an unauthenticated caller")
    void healthIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("health reports the fraud model as actually loaded, not merely configured")
    void healthReportsLoadedModel() throws Exception {
        // Non-vacuous on purpose. A health check that answers
        // {"fraudModel":"loaded"} from a static string would report the AI as
        // working while the model failed to parse -- and startup already refuses
        // to continue in that case, so the count is proof the real object exists.
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fraudModel").value("loaded"))
                .andExpect(jsonPath("$.fraudTrees").value(greaterThan(0)));
    }

    @Test
    @DisplayName("the health permitAll does not leak into neighbouring paths")
    void healthPermitAllIsAnExactPath() throws Exception {
        // These paths were all denied before /api/v1/health was added. If the
        // matcher is ever widened to a prefix, this is what notices.
        for (String path : new String[]{
                "/api/v1/users/me",
                "/api/v1/admin/couriers",
                "/api/v1/deliveries",
        }) {
            mockMvc.perform(get(path))
                    .andExpect(status().isForbidden());
        }
    }
}