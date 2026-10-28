package oak.server.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import oak.server.dto.StorageGcRunResponse;
import oak.server.dto.StorageSettingsRequest;
import oak.server.dto.StorageStatusResponse;
import oak.server.entity.Permission;
import oak.server.entity.User;
import oak.server.service.AuthorizationService;
import oak.server.service.StorageGcService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The "Server" settings screen: the storage-GC policy, a live disk-usage reading, and a
 * run-now trigger. Everything here is gated by {@link Permission#MANAGE_STORAGE} (the owner
 * holds it via ADMINISTRATOR; grant it to a role to delegate disk housekeeping).
 */
@RestController
@RequestMapping("/api/server/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageGcService storageGcService;
    private final AuthorizationService auth;

    /** Policy + the current disk usage + the last/running GC report. */
    @GetMapping
    public StorageStatusResponse status(@AuthenticationPrincipal UserDetails principal) {
        requireManageStorage(principal);
        StorageGcService.StoragePolicy policy = storageGcService.policy();
        StorageGcService.StorageUsage usage = storageGcService.usage();
        StorageGcService.StorageGcReport report = storageGcService.lastReport();
        return new StorageStatusResponse(
                new StorageStatusResponse.Policy(
                        policy.highWatermarkPercent(), policy.lowWatermarkPercent(), policy.gcMinuteOfDay(),
                        policy.gcEnabled()),
                new StorageStatusResponse.Usage(usage.totalBytes(), usage.usedBytes(), usage.usedPercent()),
                storageGcService.isRunning(),
                report == null ? null : StorageGcRunResponse.from(report));
    }

    /** Updates the policy (thresholds + the nightly run time + the automatic-cleanup switch). */
    @PutMapping
    public StorageStatusResponse update(@AuthenticationPrincipal UserDetails principal,
                                        @RequestBody @Valid StorageSettingsRequest request) {
        requireManageStorage(principal);
        if (request.highWatermarkPercent() <= request.lowWatermarkPercent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The high watermark must be above the low watermark");
        }
        storageGcService.updatePolicy(request.highWatermarkPercent(), request.lowWatermarkPercent(),
                request.gcMinuteOfDay(), request.gcEnabled() == null || request.gcEnabled());
        return status(principal);
    }

    /** Fires one GC pass NOW (202; the run itself is background — poll GET for the report).
     *  409 when a pass is already running or a backup holds the maintenance lock. */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public StorageGcRunResponse runNow(@AuthenticationPrincipal UserDetails principal) {
        requireManageStorage(principal);
        try {
            storageGcService.startManualRun();
        } catch (IllegalStateException busy) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, busy.getMessage());
        }
        return StorageGcRunResponse.started();
    }

    private void requireManageStorage(UserDetails principal) {
        User actor = auth.requireUser(principal.getUsername());
        auth.requireServer(actor, Permission.MANAGE_STORAGE);
    }
}
