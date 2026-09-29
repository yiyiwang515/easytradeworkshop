package com.dynatrace.easytrade.creditcardorderservice.models;

import lombok.Getter;

/**
 * Lifecycle states of a bitcoin payment.
 *
 * <p>A payment starts {@code PENDING} when accepted, then moves to {@code CONFIRMED} once the
 * gateway settles it on-chain, or {@code FAILED} if settlement is rejected. Keeping the request
 * thread off the settlement path (a payment is never blocked waiting for confirmation) is what lets
 * this endpoint stay fast under 100x load.
 */
@Getter
public enum BitcoinPaymentState {
    PENDING("pending", "Payment accepted and awaiting on-chain confirmation."),
    CONFIRMED("confirmed", "Payment confirmed on-chain and settled."),
    FAILED("failed", "Payment could not be settled by the gateway.");

    private final String state;
    private final String description;

    BitcoinPaymentState(String state, String description) {
        this.state = state;
        this.description = description;
    }
}
