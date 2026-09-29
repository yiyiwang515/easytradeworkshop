package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a bitcoin payment lookup or creation.
 *
 * @param paymentId     server-generated id for the payment
 * @param orderId       the credit card order this payment settles
 * @param accountId     the account the payment was made from
 * @param amountFiat    the fiat amount charged
 * @param fiatCurrency  ISO 4217 currency of {@code amountFiat}
 * @param amountBtc     the equivalent bitcoin amount at the quoted rate (null while PENDING)
 * @param state         current lifecycle state of the payment
 * @param createdAt     when the payment was accepted
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Represents the current state of a bitcoin payment.")
public record BitcoinPaymentResponse(
        String paymentId,
        String orderId,
        Integer accountId,
        BigDecimal amountFiat,
        String fiatCurrency,
        BigDecimal amountBtc,
        String state,
        OffsetDateTime createdAt) {
}
