package com.delivery.backend.modules.delivery;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Links pre-chain seals into the chain exactly once, at startup, and opens
 * the chain's head pointer and sequence.
 *
 * <p>Rows sealed before chaining shipped have a seal but no link and no
 * position. Without this step the first {@code verifyChain} on an existing
 * database would report the log broken at the second row — honestly, but
 * uselessly, since those rows were never given a predecessor. So on boot every
 * sealed row with no link is pointed at its predecessor in seal-time order and
 * numbered from 1, and the singleton head row is created (or confirmed)
 * pointing at the latest seal. Nothing else about those rows is touched: their
 * seals are evidence and are not rewritten, only linked.
 *
 * <p>The trust boundary is this method's run: anything altered before it links
 * a row is accepted as genuine — the chain protects the log from this point
 * forward, not retroactively. That is stated plainly because it is the one
 * thing the chain cannot do.
 *
 * <p>Idempotent by construction: rows that already have a link are left alone,
 * so re-running it — every restart, every test context — changes nothing. The
 * one state it refuses is sealed rows with no link on a chain that already has
 * seals: that cannot happen through the service and means someone edited the
 * tables by hand, so those rows are left for {@code verifyChain} to report.
 */
@Component
class SealChainBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SealChainBackfill.class);

    private final DeliveryRepository deliveryRepository;
    private final SealChainHeadRepository sealChainHeadRepository;

    SealChainBackfill(DeliveryRepository deliveryRepository,
                      SealChainHeadRepository sealChainHeadRepository) {
        this.deliveryRepository = deliveryRepository;
        this.sealChainHeadRepository = sealChainHeadRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Delivery> sealed = deliveryRepository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc();
        SealChainHead head = sealChainHeadRepository.findById(1L).orElseGet(() -> {
            SealChainHead created = new SealChainHead();
            created.setId(1L);
            return created;
        });

        List<Delivery> unlinked = sealed.stream()
                .filter(delivery -> delivery.getPrevHash() == null)
                .toList();
        if (!unlinked.isEmpty() && head.getLastSeq() > 0) {
            log.error("Seal chain backfill found {} sealed row(s) with no link on a chain "
                            + "that already holds {} seal(s); leaving them for verifyChain to report.",
                    unlinked.size(), head.getLastSeq());
            return;
        }

        String previous = head.getHeadHash();
        long seq = head.getLastSeq();
        boolean first = previous == null;
        for (Delivery delivery : unlinked) {
            delivery.setPrevHash(first ? DeliveryProofService.CHAIN_GENESIS : previous);
            delivery.setSealSeq(++seq);
            previous = delivery.getProofHash();
            first = false;
        }
        head.setHeadHash(previous);
        head.setLastSeq(seq);
        sealChainHeadRepository.save(head);
        if (!unlinked.isEmpty()) {
            log.info("Seal chain backfill linked {} pre-chain seal(s); seals themselves untouched.",
                    unlinked.size());
        }
    }
}
