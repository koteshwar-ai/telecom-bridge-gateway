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
    @PostMapping("/charge")
    public CompletableFuture<ChargeResponse> charge(@Valid @RequestBody ChargeRequest request) {
        return chargeService.charge(request);
    }
}
