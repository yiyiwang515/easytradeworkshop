package com.dynatrace.easytrade.creditcardorderservice;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentResponse;
import com.dynatrace.easytrade.creditcardorderservice.models.StandardResponse;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * REST endpoints for the bitcoin payment feature.
 *
 * <p>The whole feature is gated behind the {@code bitcoin_payments} OpenFeature flag (default
 * {@code false}) so it ships dark and can be enabled per-environment once the scaling and
 * reliability prerequisites in {@code docs/bitcoin-payment-architecture.md} are met. When the flag
 * is off, every endpoint returns HTTP 404 as if the route did not exist.
 */
@RestController
@RequestMapping(value = "/v1/orders/bitcoin-payments",
        produces = {"application/json", "application/xml"})
@CrossOrigin
@ApiResponses(value = {
        @ApiResponse(responseCode = "200", description = "Payment found", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "201", description = "Payment created", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "400", description = "Bad request", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "Feature disabled or payment not found", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
})
public class BitcoinPaymentController {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentController.class);
    public static final String FEATURE_FLAG = "bitcoin_payments";
    public static final String FEATURE_DISABLED = "Bitcoin payments are not enabled.";
    public static final String PAYMENT_CREATED = "Bitcoin payment accepted.";
    public static final String PAYMENT_FOUND = "Bitcoin payment found.";
    public static final String PAYMENT_NOT_FOUND = "No bitcoin payment exists for the given id.";

    private final BitcoinPaymentService paymentService;
    private final OpenFeatureAPI openFeatureAPI;

    public BitcoinPaymentController(BitcoinPaymentService paymentService, OpenFeatureAPI openFeatureAPI) {
        this.paymentService = paymentService;
        this.openFeatureAPI = openFeatureAPI;
    }

    @PostMapping(value = "", consumes = {"application/json", "application/xml"})
    @Operation(summary = "Create a bitcoin payment for a credit card order")
    public ResponseEntity<StandardResponse> createPayment(@RequestBody BitcoinPaymentRequest request) {
        if (featureDisabled()) {
            return buildResponse(HttpStatus.NOT_FOUND, FEATURE_DISABLED, null);
        }

        logger.info("Received bitcoin payment request for order {}", request.orderId());
        try {
            BitcoinPaymentResponse payment = paymentService.createPayment(request);
            return buildResponse(HttpStatus.CREATED, PAYMENT_CREATED, payment);
        } catch (IllegalArgumentException e) {
            return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);
        }
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get the status of a bitcoin payment")
    public ResponseEntity<StandardResponse> getPayment(@PathVariable String paymentId) {
        if (featureDisabled()) {
            return buildResponse(HttpStatus.NOT_FOUND, FEATURE_DISABLED, null);
        }

        Optional<BitcoinPaymentResponse> payment = paymentService.getPayment(paymentId);
        return payment
                .map(p -> buildResponse(HttpStatus.OK, PAYMENT_FOUND, p))
                .orElseGet(() -> buildResponse(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND, null));
    }

    private boolean featureDisabled() {
        final Client client = openFeatureAPI.getClient();
        return !client.getBooleanValue(FEATURE_FLAG, false);
    }

    private ResponseEntity<StandardResponse> buildResponse(HttpStatus status, String message, Object results) {
        return ResponseEntity
                .status(status)
                .body(new StandardResponse(status.value(), message, results, null, null));
    }
}
