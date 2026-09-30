package com.delivery.backend.config;

import com.delivery.backend.modules.ai.TrajectoryFraudModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liveness probe for the hosting platform.
 *
 * <p>This endpoint exists because the application had no public 2xx path at all:
 * every route answered 403, 404 or 405 to an unauthenticated GET. A platform that
 * defines "healthy = answered 2xx" therefore could never route traffic here -- the
 * backend booted perfectly and was still reported dead. Render rejected every
 * obvious candidate, which is what surfaced the gap.
 *
 * <p><b>It deliberately does not check Postgres, Redis or OSRM.</b> That is a
 * design decision, not an omission. A probe whose verdict depends on a dependency
 * conflates two different questions:
 * <ul>
 *   <li><i>liveness</i> -- "can this process still serve HTTP at all?" Must be
 *       shallow and dependency-free. Deepen it and a database blip takes the
 *       application out of rotation, and restarting it cannot fix a database
 *       that is down.</li>
 *   <li><i>readiness</i> -- "can it serve usefully right now?" Genuinely
 *       dependency-dependent, and already covered more strictly at startup: the
 *       fraud model is loaded or the process refuses to boot, and a weak JWT key
 *       aborts startup by naming the variable.</li>
 * </ul>
 *
 * <p>The startup banner is the real readiness signal. This answers only the
 * narrower question the platform needs in order to start routing traffic.
 */
@RestController
public class HealthController {

    private final TrajectoryFraudModel fraudModel;

    /**
     * Injected rather than reported from configuration. The bean either exists --
     * meaning the exported model was read and parsed -- or the context failed to
     * start and this controller is never constructed. Reading a config key here
     * would report what was <i>intended</i> to load, which is a different and
     * weaker claim than what actually did.
     */
    public HealthController(TrajectoryFraudModel fraudModel) {
        this.fraudModel = fraudModel;
    }

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("fraudModel", "loaded");
        body.put("fraudTrees", fraudModel.treeCount());
        return body;
    }
}