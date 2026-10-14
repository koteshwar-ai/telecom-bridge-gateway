package com.pyrogroup.telecom_bridge_gateway.web;

import com.pyrogroup.telecom_bridge_gateway.charge.ChargeRequest;
import com.pyrogroup.telecom_bridge_gateway.charge.ChargeResponse;
import com.pyrogroup.telecom_bridge_gateway.charge.ChargeService;
import jakarta.validation.Valid;
import java.util.concurrent.CompletableFuture;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

@RestController
@RequestMapping("/api/v1")
public class ChargeController {

    private final ChargeService chargeService;

    public ChargeController(ChargeService chargeService) {
        this.chargeService = chargeService;
    }

    /**
     * Returning a CompletableFuture makes Spring MVC start async processing: the servlet thread is
     * released immediately and the response is written when the Diameter CCA arrives.
     */
    @Operation(summary = "Charge a subscriber via Diameter CCR/CCA")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "CCA received (APPROVED or REJECTED)"),
            @ApiResponse(responseCode = "400", description = "Invalid request body"),
            @ApiResponse(responseCode = "502", description = "Peer returned a Diameter error"),
            @ApiResponse(responseCode = "503", description = "Diameter peer down or overloaded"),
            @ApiResponse(responseCode = "504", description = "No CCA within timeout")
    })
    @PostMapping("/charge")
    public CompletableFuture<ChargeResponse> charge(@Valid @RequestBody ChargeRequest request) {
        return chargeService.charge(request);
    }
}
