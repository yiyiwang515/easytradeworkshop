package com.dynatrace.easytrade.creditcardorderservice;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentResponse;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentState;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinQuote;

/**
 * Coordinates bitcoin payments: accepts a request, asks the gateway to settle it, and records the
 * resulting state.
 *
 * <p><b>Persistence:</b> this implementation keeps payment records in an in-memory concurrent map.
 * That is deliberate and scoped for this PR — moving payment rows into a {@code BitcoinPayments}
 * table is gated on introducing a pooled {@code DataSource} (see
 * {@code docs/bitcoin-payment-architecture.md} §2.3), which touches every DB call in the service and
 * is being handled as a separate reviewed change. The feature ships behind a flag (default off) so
 * no unpooled DB writes reach production before that migration lands.
 */
@Service
public class BitcoinPaymentService {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentService.class);

    private final BitcoinPaymentGatewayClient gatewayClient;
    private final ConcurrentHashMap<String, BitcoinPaymentResponse> payments = new ConcurrentHashMap<>();

    public BitcoinPaymentService(BitcoinPaymentGatewayClient gatewayClient) {
        this.gatewayClient = gatewayClient;
    }

    /**
     * Creates a new bitcoin payment. The request thread never blocks on on-chain confirmation:
     * if the gateway accepts synchronously we record {@code CONFIRMED}, otherwise the payment is
     * recorded {@code PENDING} for later reconciliation.
     */
    public BitcoinPaymentResponse createPayment(BitcoinPaymentRequest request) {
        String paymentId = UUID.randomUUID().toString();
        logger.info("Creating bitcoin payment {} for order {} (account {})",
                paymentId, request.orderId(), request.accountId());

        Optional<BitcoinQuote> quote = gatewayClient.requestSettlement(
                request.walletAddress(), request.amountFiat(), request.fiatCurrency());

        BitcoinPaymentState state;
        BigDecimal amountBtc = null;
        if (quote.isEmpty()) {
            // Gateway unreachable/timed out: keep it pending, do not fail the caller.
            state = BitcoinPaymentState.PENDING;
        } else if (quote.get().accepted()) {
            state = BitcoinPaymentState.CONFIRMED;
            amountBtc = quote.get().amountBtc();
        } else {
            state = BitcoinPaymentState.FAILED;
            amountBtc = quote.get().amountBtc();
        }

        BitcoinPaymentResponse payment = new BitcoinPaymentResponse(
                paymentId,
                request.orderId(),
                request.accountId(),
                request.amountFiat(),
                request.fiatCurrency(),
                amountBtc,
                state.getState(),
                OffsetDateTime.now());

        payments.put(paymentId, payment);
        logger.info("Bitcoin payment {} recorded in state {}", paymentId, state.getState());
        return payment;
    }

    /** Looks up a previously created bitcoin payment by id. */
    public Optional<BitcoinPaymentResponse> getPayment(String paymentId) {
        return Optional.ofNullable(payments.get(paymentId));
    }
}
