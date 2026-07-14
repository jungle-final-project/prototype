package com.buildgraph.prototype.assembly;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AssemblyBrokerageController {
    private final AssemblyBrokerageService service;

    public AssemblyBrokerageController(AssemblyBrokerageService service) {
        this.service = service;
    }

    @PostMapping("/assembly-requests")
    Map<String, Object> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody Map<String, Object> request
    ) {
        return service.create(authorization, idempotencyKey, request);
    }

    @GetMapping("/assembly-requests")
    Map<String, Object> list(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return service.listForUser(authorization, page, size);
    }

    @GetMapping("/assembly-requests/{id}")
    Map<String, Object> detail(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.detailForUser(authorization, id);
    }

    @PostMapping("/assembly-requests/{id}/offers/{offerId}/select")
    Map<String, Object> selectOffer(
            @PathVariable String id,
            @PathVariable String offerId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.selectOffer(authorization, id, offerId);
    }

    @PostMapping("/assembly-requests/{id}/payments/confirm-virtual")
    Map<String, Object> confirmVirtualPayment(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.confirmVirtualPayment(authorization, id);
    }

    @PostMapping("/assembly-requests/{id}/cancel")
    Map<String, Object> cancel(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.cancelForUser(authorization, id, request);
    }

    @GetMapping("/admin/technicians")
    Map<String, Object> technicians(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "providerType", required = false) String providerType,
            @RequestParam(value = "verificationStatus", required = false) String verificationStatus,
            @RequestParam(value = "includeDeleted", required = false) Boolean includeDeleted,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return service.listTechnicians(authorization, query, status, region, providerType, verificationStatus, includeDeleted, page, size);
    }

    @PostMapping("/admin/technicians")
    Map<String, Object> createTechnician(@RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return service.createTechnician(authorization, request);
    }

    @GetMapping("/admin/technicians/{id}")
    Map<String, Object> technician(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.technicianDetail(authorization, id);
    }

    @PatchMapping("/admin/technicians/{id}")
    Map<String, Object> updateTechnician(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return service.updateTechnician(authorization, id, request);
    }

    @DeleteMapping("/admin/technicians/{id}")
    Map<String, Object> deleteTechnician(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.deleteTechnician(authorization, id);
    }

    @PostMapping("/admin/technicians/{id}/restore")
    Map<String, Object> restoreTechnician(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.restoreTechnician(authorization, id);
    }

    @PostMapping("/admin/technicians/{id}/approve")
    Map<String, Object> approveTechnician(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.approveTechnician(authorization, id);
    }

    @PostMapping("/admin/technicians/{id}/reject")
    Map<String, Object> rejectTechnician(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.rejectTechnician(authorization, id, request);
    }

    @GetMapping("/admin/assembly-requests")
    Map<String, Object> adminRequests(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "region", required = false) String region,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return service.listAdminRequests(authorization, status, region, query, page, size);
    }

    @GetMapping("/admin/assembly-requests/{id}")
    Map<String, Object> adminRequest(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        return service.adminRequestDetail(authorization, id);
    }

    @PatchMapping("/admin/assembly-requests/{id}/status")
    Map<String, Object> updateRequestStatus(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return service.updateAdminRequestStatus(authorization, id, request);
    }

    @PostMapping("/admin/assembly-requests/{id}/offers")
    Map<String, Object> createOffer(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return service.createAdminOffer(authorization, id, request);
    }

    @PatchMapping("/admin/assembly-requests/{id}/offers/{offerId}")
    Map<String, Object> updateOffer(@PathVariable String id, @PathVariable String offerId, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return service.updateAdminOffer(authorization, id, offerId, request);
    }

    @PostMapping("/admin/assembly-requests/{id}/offers/{offerId}/withdraw")
    Map<String, Object> withdrawOffer(@PathVariable String id, @PathVariable String offerId, @RequestHeader(value = "Authorization", required = false) String authorization, @RequestBody Map<String, Object> request) {
        return service.withdrawAdminOffer(authorization, id, offerId, request);
    }
}
