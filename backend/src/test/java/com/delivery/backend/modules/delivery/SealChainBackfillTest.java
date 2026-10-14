package com.delivery.backend.modules.delivery;

import com.delivery.backend.modules.auth.Role;
import com.delivery.backend.modules.auth.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup backfill, against mocked repositories.
 *
 * <p>The backfill is what makes {@code verifyChain} green on a database that
 * already holds sealed rows: it links and numbers them without rewriting a
 * single seal. These tests pin that it touches nothing it shouldn't — linked
 * rows are left alone, reruns are no-ops, and the one state that cannot happen
 * through the service is left for the chain to report rather than papered over.
 */
class SealChainBackfillTest {

    private static final LocalDateTime T1 = LocalDateTime.of(2026, 3, 1, 10, 0, 0);
    private static final LocalDateTime T2 = LocalDateTime.of(2026, 3, 1, 11, 0, 0);

    private final DeliveryRepository repository = mock(DeliveryRepository.class);
    private final SealChainHeadRepository headRepository = mock(SealChainHeadRepository.class);
    private final SealChainBackfill backfill = new SealChainBackfill(repository, headRepository);

    private static Delivery sealedRow(LocalDateTime at) {
        Delivery delivery = new Delivery("parcel", "pickup", "dropoff",
                new User("client@swift.com", "x", Role.CLIENT));
        delivery.setId(UUID.randomUUID());
        delivery.setDeliveredAt(at);
        delivery.setDeliveredLat(33.5731);
        delivery.setDeliveredLng(-7.5898);
        delivery.setProofHash("s".repeat(64));
        return delivery;
    }

    @Test
    @DisplayName("pre-chain rows are linked and numbered, and the head is opened")
    void preChainRowsAreLinkedAndNumbered() {
        Delivery first = sealedRow(T1);
        Delivery second = sealedRow(T2);
        second.setProofHash("t".repeat(64));
        when(repository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc())
                .thenReturn(List.of(first, second));
        when(headRepository.findById(1L)).thenReturn(Optional.empty());

        backfill.run(null);

        assertEquals(DeliveryProofService.CHAIN_GENESIS, first.getPrevHash());
        assertEquals(1L, first.getSealSeq());
        assertEquals(first.getProofHash(), second.getPrevHash());
        assertEquals(2L, second.getSealSeq());

        org.mockito.ArgumentCaptor<SealChainHead> heads =
                org.mockito.ArgumentCaptor.forClass(SealChainHead.class);
        verify(headRepository).save(heads.capture());
        assertEquals(1L, heads.getValue().getId());
        assertEquals(second.getProofHash(), heads.getValue().getHeadHash());
        assertEquals(2L, heads.getValue().getLastSeq());
    }

    @Test
    @DisplayName("a rerun on a linked chain changes nothing")
    void aRerunChangesNothing() {
        Delivery first = sealedRow(T1);
        first.setPrevHash(DeliveryProofService.CHAIN_GENESIS);
        first.setSealSeq(1L);
        when(repository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc())
                .thenReturn(List.of(first));
        SealChainHead head = new SealChainHead();
        head.setId(1L);
        head.setHeadHash(first.getProofHash());
        head.setLastSeq(1L);
        when(headRepository.findById(1L)).thenReturn(Optional.of(head));

        backfill.run(null);

        assertEquals(DeliveryProofService.CHAIN_GENESIS, first.getPrevHash());
        assertEquals(1L, first.getSealSeq());
        assertEquals(first.getProofHash(), head.getHeadHash());
        assertEquals(1L, head.getLastSeq());
    }

    @Test
    @DisplayName("an empty database still opens an empty head")
    void anEmptyDatabaseOpensAnEmptyHead() {
        when(repository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc())
                .thenReturn(List.of());
        when(headRepository.findById(1L)).thenReturn(Optional.empty());

        backfill.run(null);

        org.mockito.ArgumentCaptor<SealChainHead> heads =
                org.mockito.ArgumentCaptor.forClass(SealChainHead.class);
        verify(headRepository).save(heads.capture());
        assertNull(heads.getValue().getHeadHash());
        assertEquals(0L, heads.getValue().getLastSeq());
    }

    @Test
    @DisplayName("unlinked rows on a chain that already has seals are left for verifyChain to report")
    void unlinkedRowsOnAUsedChainAreLeftAlone() {
        Delivery stray = sealedRow(T2);
        when(repository.findByProofHashNotNullOrderByDeliveredAtAscIdAsc())
                .thenReturn(List.of(stray));
        SealChainHead head = new SealChainHead();
        head.setId(1L);
        head.setHeadHash("h".repeat(64));
        head.setLastSeq(5L);
        when(headRepository.findById(1L)).thenReturn(Optional.of(head));

        backfill.run(null);

        assertNull(stray.getPrevHash(), "hand-edited rows must not be quietly adopted");
        assertNull(stray.getSealSeq());
    }
}
