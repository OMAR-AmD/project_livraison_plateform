package com.delivery.backend.modules.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Links pre-chain seals into the chain exactly once, at startup.
 *
 * <p>Rows sealed before chaining shipped have a seal but no link. Without this
 * step the first {@code verifyChain} on an existing database would report the
 * log broken at the second row — honestly, but uselessly, since those rows
 * were never given a predecessor. So on boot every sealed row with no link is
 * pointed at its predecessor in chain order (or {@code GENESIS} for the very
 * first), and nothing else about those rows is touched: their seals are
 * evidence and are not rewritten, only linked.
 *
 * <p>Idempotent by construction: rows that already have a link are left alone,
 * so re-running it — every restart, every test context — changes nothing.
 */
@Component
class SealChainBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SealChainBackfill.class);

    private final DeliveryRepository deliveryRepository;

    SealChainBackfill(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Delivery> sealed = deliveryRepository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc();
        String previous = null;
        boolean first = true;
        int linked = 0;
        for (Delivery delivery : sealed) {
            if (delivery.getPrevHash() == null) {
                delivery.setPrevHash(first ? DeliveryProofService.CHAIN_GENESIS : previous);
                linked++;
            }
            previous = delivery.getProofHash();
            first = false;
        }
        if (linked > 0) {
            log.info("Seal chain backfill linked {} pre-chain seal(s); seals themselves untouched.", linked);
        }
    }
}
