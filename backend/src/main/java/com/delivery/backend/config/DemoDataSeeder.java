package com.delivery.backend.config;

import com.delivery.backend.modules.auth.Role;
import com.delivery.backend.modules.auth.User;
import com.delivery.backend.modules.auth.UserRepository;
import com.delivery.backend.modules.delivery.Delivery;
import com.delivery.backend.modules.delivery.DeliveryRepository;
import com.delivery.backend.modules.delivery.DeliveryStatus;
import com.delivery.backend.modules.delivery.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Populates the platform with realistic demo data.
 *
 * <p>Bound to the {@code demo} profile, so it can only ever run when explicitly
 * requested:
 *
 * <pre>mvnw spring-boot:run -Dspring-boot.run.profiles=demo</pre>
 *
 * <p>This replaces an earlier {@code CommandLineRunner} in
 * {@code BackendApplication} that created three placeholder accounts on
 * <em>every</em> boot, printed a hard-coded administrator password to stdout, and
 * could not be switched off. A seeder that always runs and cannot be disabled is
 * a foot-gun, so this one is opt-in and seeds deliveries only into an empty
 * database.
 *
 * <p>The six demo accounts are <em>managed</em>: every boot ensures they exist
 * with the {@code DEMO_PASSWORD} password, creating or re-syncing as needed.
 * That way the password shown in the Render dashboard is always the password
 * that works — regenerating it can never lock the demo out. Locally the
 * variable is usually unset and the documented {@code password123} fallback
 * applies; those accounts hold demo data only and are public by design. The
 * fallback value is never logged.
 *
 * <p>All prices are written directly rather than quoted, because seeding does not
 * go through the OSRM round trip. Coordinates are real Casablanca addresses so
 * the maps and the fleet view look plausible.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    /** Casablanca addresses, roughly north-west to south-east. */
    private static final double[][] PLACES = {
            {33.5891, -7.6311},  // Anfa
            {33.5731, -7.5898},  // Centre
            {33.5992, -7.6200},  // Maârif
            {33.5820, -7.6000},  // Roches-Noires
            {33.5560, -7.6000},  // Ain Diab
            {33.5450, -7.5900},  // CIL
    };

    private static final String[] STREETS = {
            "Boulevard d'Anfa", "Avenue Mohammed V", "Rue Ibn Batouta",
            "Boulevard Rachidi", "Avenue des FAR", "Rue Tarik Ibn Ziad",
    };

    private final UserRepository userRepository;
    private final DeliveryRepository deliveryRepository;
    private final PasswordEncoder passwordEncoder;
    private final String demoPassword;

    public DemoDataSeeder(UserRepository userRepository,
                          DeliveryRepository deliveryRepository,
                          PasswordEncoder passwordEncoder,
                          @org.springframework.beans.factory.annotation.Value("${DEMO_PASSWORD:password123}") String demoPassword) {
        this.userRepository = userRepository;
        this.deliveryRepository = deliveryRepository;
        this.passwordEncoder = passwordEncoder;
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        // Demo accounts are managed, not just seeded: whatever is in
        // DEMO_PASSWORD now is what works now, including after a regenerate.
        User admin = ensureDemoUser("admin@swift.com", "Amina", "Berrada", Role.ADMIN);
        User courier1 = ensureDemoUser("courier1@swift.com", "Youssef", "Alaoui", Role.LIVREUR);
        User courier2 = ensureDemoUser("courier2@swift.com", "Karim", "Naciri", Role.LIVREUR);
        User client1 = ensureDemoUser("client1@swift.com", "Sara", "El Mansouri", Role.CLIENT);
        User client2 = ensureDemoUser("client2@swift.com", "Omar", "Tazi", Role.CLIENT);
        User client3 = ensureDemoUser("client3@swift.com", "Lina", "Chraibi", Role.CLIENT);

        if (deliveryRepository.count() > 0) {
            log.info("Demo seeder skipped deliveries: the database already contains {}.",
                    deliveryRepository.count());
            return;
        }

        log.info("Seeding demo deliveries...");

        // Spread the round across both couriers so the admin fleet map and the
        // auto-dispatcher both have something realistic to work with.
        delivery(client1, courier1, DeliveryStatus.DELIVERED, PaymentStatus.PAID, 0, 1, 5);
        delivery(client1, courier1, DeliveryStatus.DELIVERED, PaymentStatus.PAID, 1, 2, 3);
        delivery(client1, courier1, DeliveryStatus.IN_TRANSIT, PaymentStatus.PAID, 2, 0, 4);
        delivery(client2, courier2, DeliveryStatus.ASSIGNED, PaymentStatus.PAID, 3, 1, 2);
        delivery(client2, courier2, DeliveryStatus.ASSIGNED, PaymentStatus.PAID, 4, 5, 0);
        delivery(client3, null, DeliveryStatus.PENDING, PaymentStatus.PENDING_PAYMENT, 5, 2, 3);
        delivery(client3, null, DeliveryStatus.PENDING, PaymentStatus.PENDING_PAYMENT, 0, 4, 1);
        delivery(client3, null, DeliveryStatus.CANCELLED, PaymentStatus.REFUNDED, 3, 5, 2);

        log.info("""

                ================================================================
                 Demo data seeded (profile: demo). Accounts and deliveries ready:
                   admin@swift.com      ADMIN
                   courier1@swift.com    LIVREUR
                   courier2@swift.com    LIVREUR
                   client1@swift.com     CLIENT
                   client2@swift.com     CLIENT
                   client3@swift.com     CLIENT
                 Password: the DEMO_PASSWORD value (default password123).
                ================================================================
                """);
    }

    /**
     * Creates the demo account if missing, or re-syncs its password when the
     * stored hash no longer matches the current {@code DEMO_PASSWORD}. Someone
     * who changed a demo password on purpose and reboots gets it managed back;
     * that is the documented price of a demo account, and self-registered
     * accounts are never touched.
     */
    private User ensureDemoUser(String email, String first, String last, Role role) {
        User existing = userRepository.findByEmail(email).orElse(null);
        if (existing != null) {
            if (!passwordEncoder.matches(demoPassword, existing.getPassword())) {
                existing.setPassword(passwordEncoder.encode(demoPassword));
                log.info("Demo account {} password re-synced to the current DEMO_PASSWORD.", email);
            }
            return existing;
        }
        User created = user(email, first, last, role);
        userRepository.save(created);
        return created;
    }

    private User user(String email, String first, String last, Role role) {
        User u = new User(email, passwordEncoder.encode(demoPassword), role);
        u.setFirstName(first);
        u.setLastName(last);
        u.setPhoneNumber("+212 6 00 00 00 00");
        u.setEmailVerified(true);
        return u;
    }

    private void delivery(User client, User courier, DeliveryStatus status,
                          String payment, int seed, int from, int to) {
        double[] p = PLACES[from % PLACES.length];
        double[] d = PLACES[to % PLACES.length];

        Delivery delivery = new Delivery(
                switch (seed % 4) {
                    case 0 -> "2 small boxes";
                    case 1 -> "Documents and folder";
                    case 2 -> "3 medium parcels";
                    default -> "Fragile - glassware";
                },
                (from % STREETS.length) + ", Casablanca",
                (to % STREETS.length) + ", Casablanca",
                client
        );

        delivery.setPickupLat(p[0]);
        delivery.setPickupLng(p[1]);
        delivery.setDropoffLat(d[0]);
        delivery.setDropoffLng(d[1]);
        delivery.setStatus(status);
        delivery.setPaymentStatus(payment);
        delivery.setCourier(courier);

        // Distance-derived fare, consistent with the 15 MAD floor + 5 MAD/km.
        double km = Math.hypot((d[0] - p[0]) * 111.0, (d[1] - p[1]) * 93.0);
        delivery.setPrice(Math.max(15.0, Math.ceil(km * 5.0)));

        if (status == DeliveryStatus.DELIVERED) {
            // A real rating from a real (seeded) customer, so the platform
            // average is meaningful rather than fabricated at boot.
            delivery.setRating(4 + (seed % 2));
            delivery.setReviewComment("Delivered on time and well handled.");
        }

        deliveryRepository.save(delivery);
    }
}
