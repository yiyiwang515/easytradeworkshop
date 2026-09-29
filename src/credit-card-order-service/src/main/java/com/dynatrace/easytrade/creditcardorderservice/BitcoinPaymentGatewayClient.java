package com.dynatrace.easytrade.creditcardorderservice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinQuote;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client for the external bitcoin payment gateway.
 *
 * <p>The gateway is the one dependency in the bitcoin flow whose latency and availability we do not
 * control, so every call is bounded by an explicit connect/request timeout. A gateway that is slow
 * or down must never hold a request thread open — under 100x load that would exhaust the thread
 * pool and take the whole service down. On any failure we return an empty {@link Optional} and the
 * caller records the payment as {@code PENDING} for later reconciliation rather than failing the
 * request.
 *
 * <p>If {@code BITCOIN_GATEWAY_URL} is not configured, the client runs in a deterministic local
 * simulation mode so the feature works in dev/compose without a real gateway.
 */
@Component
public class BitcoinPaymentGatewayClient {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentGatewayClient.class);

    /** Fallback BTC/USD-ish rate used only in local simulation mode. */
    private static final BigDecimal SIMULATED_RATE = new BigDecimal("60000");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Duration requestTimeout = Duration.ofSeconds(3);
    private final String gatewayUrl = System.getenv("BITCOIN_GATEWAY_URL");

    /**
     * Requests a settlement quote from the gateway.
     *
     * @return the quote, or {@link Optional#empty()} if the gateway could not be reached in time.
     */
    public Optional<BitcoinQuote> requestSettlement(String walletAddress, BigDecimal amountFiat, String fiatCurrency) {
        if (gatewayUrl == null || gatewayUrl.isBlank()) {
            return Optional.of(simulate(amountFiat));
        }

        try {
            String payload = mapper.writeValueAsString(new SettlementRequest(walletAddress, amountFiat, fiatCurrency));
            // deepcode ignore Ssrf: trusted environment variable
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(gatewayUrl + "/v1/settlements"))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                logger.warn("Bitcoin gateway returned status {}", response.statusCode());
                return Optional.empty();
            }

            JsonNode body = mapper.readTree(response.body());
            BigDecimal amountBtc = body.path("amountBtc").decimalValue();
            boolean accepted = body.path("accepted").asBoolean(false);
            return Optional.of(new BitcoinQuote(amountBtc, accepted));
        } catch (Exception e) {
            // Timeouts, connection errors and parse errors all degrade to "unknown" so the payment
            // stays PENDING instead of 500-ing the caller.
            logger.warn("Bitcoin gateway settlement request failed, treating payment as pending", e);
            return Optional.empty();
        }
    }

    private BitcoinQuote simulate(BigDecimal amountFiat) {
        BigDecimal amountBtc = amountFiat.divide(SIMULATED_RATE, 8, RoundingMode.HALF_UP);
        return new BitcoinQuote(amountBtc, true);
    }

    private record SettlementRequest(String walletAddress, BigDecimal amountFiat, String fiatCurrency) {
    }
}
