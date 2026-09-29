package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;

/**
 * A quote returned by the bitcoin payment gateway: how much BTC settles a given fiat amount,
 * and whether the gateway accepted the settlement request.
 *
 * @param amountBtc  the bitcoin amount equivalent to the requested fiat amount
 * @param accepted   whether the gateway accepted the payment for settlement
 */
public record BitcoinQuote(BigDecimal amountBtc, boolean accepted) {
}
