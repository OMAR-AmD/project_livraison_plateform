package com.delivery.backend.config;

import com.delivery.backend.modules.auth.Role;
import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.auth.UserRepository;
import com.delivery.backend.modules.delivery.Delivery;
import com.delivery.backend.modules.delivery.DeliveryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The demo seeder manages its accounts: whatever {@code DEMO_PASSWORD} holds
 * at boot is what works afterwards, so regenerating the value can never lock
 * the demo out. Deliveries, unlike accounts, are seeded once into an empty
 * database and never duplicated.
 */
class DemoDataSeederTest {

    private static final String[] DEMO_EMAILS = {
            "admin@swift.com", "courier1@swift.com", "courier2@swift.com",
            "client1@swift.com", "client2@swift.com", "client3@swift.com"};

    private final UserRepository users = mock(UserRepository.class);
    private final DeliveryRepository deliveries = mock(DeliveryRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private DemoDataSeeder seeder() {
        return new DemoDataSeeder(users, deliveries, encoder, "demo-pw");
    }

    private static User existing(String email, PasswordEncoder encoder, String password) {
        User user = new User(email, encoder.encode(password), Role.CLIENT);
        user.setId(java.util.UUID.randomUUID());
        return user;
    }

    @Test
    @DisplayName("an empty database gets six accounts on the current password plus deliveries")
    void emptyDatabaseIsSeeded() {
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(deliveries.count()).thenReturn(0L);

        seeder().run(null);

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users, times(6)).save(saved.capture());
        assertEquals(6, saved.getAllValues().stream().map(User::getEmail).distinct().count());
        assertTrue(saved.getAllValues().stream()
                .allMatch(u -> encoder.matches("demo-pw", u.getPassword())));
        verify(deliveries, times(8)).save(any(Delivery.class));
    }

    @Test
    @DisplayName("a stale demo password is re-synced without duplicating deliveries")
    void stalePasswordIsResynced() {
        User admin = existing("admin@swift.com", encoder, "old-value");
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        when(users.findByEmail("admin@swift.com")).thenReturn(Optional.of(admin));
        when(deliveries.count()).thenReturn(1L);

        seeder().run(null);

        assertTrue(encoder.matches("demo-pw", admin.getPassword()),
                "the dashboard value must work after a regenerate");
        verify(deliveries, never()).save(any(Delivery.class));
    }

    @Test
    @DisplayName("matching accounts are left alone")
    void matchingAccountsAreUntouched() {
        for (String email : DEMO_EMAILS) {
            when(users.findByEmail(email))
                    .thenReturn(Optional.of(existing(email, encoder, "demo-pw")));
        }
        when(deliveries.count()).thenReturn(1L);

        seeder().run(null);

        verify(users, never()).save(any(User.class));
        verify(deliveries, never()).save(any(Delivery.class));
    }
}
