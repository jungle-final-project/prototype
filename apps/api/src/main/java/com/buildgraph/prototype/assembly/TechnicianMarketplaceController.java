package com.buildgraph.prototype.assembly;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/technician")
public class TechnicianMarketplaceController {
    private final TechnicianMarketplaceService service;
    private final TechnicianProfileImageService profileImageService;

    public TechnicianMarketplaceController(TechnicianMarketplaceService service, TechnicianProfileImageService profileImageService) {
        this.service = service;
        this.profileImageService = profileImageService;
    }

    @PostMapping("/applications")
    Map<String, Object> apply(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.apply(authorization, request);
    }

    @GetMapping("/profile")
    ResponseEntity<Map<String, Object>> profile(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.profileIfPresent(authorization)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PatchMapping("/profile")
    Map<String, Object> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.updateProfile(authorization, request);
    }

    @PostMapping(value = "/profile-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Map<String, Object> uploadProfileImage(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam("file") MultipartFile file
    ) {
        service.requireProfileImageUpload(authorization);
        return profileImageService.upload(file);
    }

    @GetMapping("/assembly-requests")
    Map<String, Object> requests(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "scope", required = false) String scope,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size
    ) {
        return service.listRequests(authorization, scope, page, size);
    }

    @GetMapping("/assembly-requests/{id}")
    Map<String, Object> request(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return service.requestDetail(authorization, id);
    }

    @PostMapping("/assembly-requests/{id}/offers")
    Map<String, Object> createOffer(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.createOffer(authorization, id, request);
    }

    @PatchMapping("/offers/{id}")
    Map<String, Object> updateOffer(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.updateOffer(authorization, id, request);
    }

    @PostMapping("/offers/{id}/withdraw")
    Map<String, Object> withdrawOffer(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody Map<String, Object> request
    ) {
        return service.withdrawOffer(authorization, id, request);
    }
}
