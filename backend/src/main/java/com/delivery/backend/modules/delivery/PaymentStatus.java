package com.delivery.backend.modules.delivery;

/**
 * Values stored in {@code Delivery.paymentStatus}.
 *
 * <p>Revenue is recognised only on {@link #PAID}, which is set when payment is
 * captured rather than when the order is created. That distinction is what allows an
 * unpaid or failed order to exist in the database without inflating the revenue figure.
 */
public final class PaymentStatus {

    /** Order created, payment not yet captured. */
    public static final String PENDING_PAYMENT = "PENDING_PAYMENT";

    /** Payment captured. This is the only state that counts towards revenue. */
    public static final String PAID = "PAID";

    /** Captured payment returned to the client after cancellation. */
    public static final String REFUNDED = "REFUNDED";

    /** Order cancelled before any money was captured. */
    public static final String VOIDED = "VOIDED";

    private PaymentStatus() {}
}
