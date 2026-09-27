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
import java.util.List;

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
 * a foot-gun, so this one is opt-in and refuses to write to a database that is
 * not already empty.
 *
 * <p>All prices are written directly rather than quoted, because seeding does not
 * go through the OSRM round trip. Coordinates are real Casablanca addresses so
 * the maps and the fleet view look plausible.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String PASSWORD = "password123";

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

    public DemoDataSeeder(UserRepository userRepository,
                          DeliveryRepository deliveryRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.deliveryRepository = deliveryRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            log.info("Demo seeder skipped: the database already contains {} user(s).",
                    userRepository.count());
            return;
        }

        log.info("Seeding demo data...");

        User admin = user("admin@swift.com", "Amina", "Berrada", Role.ADMIN);
        User courier1 = user("courier1@swift.com", "Youssef", "Alaoui", Role.LIVREUR);
        User courier2 = user("courier2@swift.com", "Karim", "Naciri", Role.LIVREUR);
        User client1 = user("client1@swift.com", "Sara", "El Mansouri", Role.CLIENT);
        User client2 = user("client2@swift.com", "Omar", "Tazi", Role.CLIENT);
        User client3 = user("client3@swift.com", "Lina", "Chraibi", Role.CLIENT);

        userRepository.saveAll(List.of(admin, courier1, courier2, client1, client2, client3));

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
                 Demo data seeded (profile: demo). All passwords: {}
                   admin@swift.com      ADMIN
                   courier1@swift.com    LIVREUR
                   courier2@swift.com    LIVREUR
                   client1@swift.com     CLIENT
                   client2@swift.com     CLIENT
                   client3@swift.com     CLIENT
                ================================================================
                """, PASSWORD);
    }

    private User user(String email, String first, String last, Role role) {
        User u = new User(email, passwordEncoder.encode(PASSWORD), role);
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
