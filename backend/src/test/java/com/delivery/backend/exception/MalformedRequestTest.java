package com.delivery.backend.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A request the server cannot read is the caller's mistake and must be reported
 * as such.
 *
 * <p>Every case here is one Spring's own resolver would answer with 400. They
 * returned 500 instead, because {@link GlobalExceptionHandler#handleGenericException}
 * matches {@code Exception} and an advice handler runs before the default
 * resolver. Three consequences, in increasing order of how much they cost: the
 * caller is told the server broke when their request did; the message naming the
 * bad field is thrown away; and a burst of broken clients looks like an incident.
 *
 * <p>These assertions would pass against the default resolver too, so they only
 * have teeth while the catch-all exists -- which is precisely the situation. Each
 * one fails with 500 if the specific handler is removed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MalformedRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("a truncated JSON body is a 400, not a 500")
    void malformedBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"someone@swift.com\", \"password\": "))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a body that is not JSON at all is a 400, not a 500")
    void nonJsonBody() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("this is not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a missing required query parameter is a 400, not a 500")
    void missingParameter() throws Exception {
        // /auth/verify is public and takes ?token=, so this is reachable without
        // credentials: exactly the endpoint a stray crawler would hit.
        mockMvc.perform(get("/api/v1/auth/verify"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a path variable that is not a UUID is a 400, not a 500")
    void wrongPathVariableType() throws Exception {
        mockMvc.perform(get("/api/v1/admin/deliveries/not-a-uuid/proof")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }
}
