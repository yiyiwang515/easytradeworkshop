package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request payload for initiating a bitcoin payment against a credit-card order.
 *
 * @param accountId       the account the payment is made from
 * @param orderId         the credit card order the payment settles
 * @param amountFiat      the amount to charge, in the account's fiat currency
 * @param fiatCurrency    ISO 4217 currency code of {@code amountFiat} (e.g. "USD")
 * @param walletAddress   the customer's bitcoin wallet address funding the payment
 */
@Schema(description = "Initiates a bitcoin payment for a credit card order.")
public record BitcoinPaymentRequest(
        Integer accountId,
        String orderId,
        BigDecimal amountFiat,
        String fiatCurrency,
        String walletAddress) {
    public BitcoinPaymentRequest {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(orderId, "orderId must not be null");
        Objects.requireNonNull(amountFiat, "amountFiat must not be null");
        Objects.requireNonNull(fiatCurrency, "fiatCurrency must not be null");
        Objects.requireNonNull(walletAddress, "walletAddress must not be null");

        if (amountFiat.signum() <= 0) {
            throw new IllegalArgumentException("amountFiat must be greater than zero");
        }
    }
}
