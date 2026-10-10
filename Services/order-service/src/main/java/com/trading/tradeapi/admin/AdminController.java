package com.trading.tradeapi.admin;

import com.trading.tradeapi.dto.AdjustBalanceRequestDto;
import com.trading.tradeapi.dto.AdminAccountDto;
import com.trading.tradeapi.dto.AdminAccountStatusDto;
import com.trading.tradeapi.dto.AdminOrderDto;
import com.trading.tradeapi.dto.AdminStatsDto;
import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.dto.ApproveUserRequestDto;
import com.trading.tradeapi.dto.SuspendUserRequestDto;
import com.trading.tradeapi.dto.CreateInstrumentRequestDto;
import com.trading.tradeapi.dto.InstrumentDto;
import com.trading.tradeapi.dto.UpdateStatusRequestDto;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;
    private final AdminAuthorizer authorizer;
    private final AdminNotifications notifications;

    public AdminController(AdminService adminService,
                           AdminAuthorizer authorizer,
                           AdminNotifications notifications) {
        this.adminService = adminService;
        this.authorizer = authorizer;
        this.notifications = notifications;
    }

    @GetMapping("/stats")
    public ResponseEntity<AdminStatsDto> getStats(HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.getStats());
    }

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserDto>> getUsers(HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.getUsers());
    }

    @GetMapping("/users/pending")
    public ResponseEntity<List<AdminUserDto>> getPendingUsers(HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.getPendingUsers());
    }

    @PostMapping("/users/{id}/approve")
    public ResponseEntity<AdminUserDto> approveUser(@PathVariable("id") Long id,
                                                   @RequestBody(required = false) ApproveUserRequestDto body,
                                                   HttpServletRequest request) {
        String actor = authorizer.requireAdmin(request);
        AdminUserDto user = adminService.approveUser(id, actor, body);
        // The approval has committed; tell the applicant their account is ready.
        return ResponseEntity.ok(notifications.approved(user));
    }

    @PostMapping("/users/{id}/reinstate")
    public ResponseEntity<AdminUserDto> reinstateUser(@PathVariable("id") Long id,
                                                     @RequestBody(required = false) SuspendUserRequestDto body,
                                                     HttpServletRequest request) {
        String actor = authorizer.requireAdmin(request);
        AdminUserDto user = adminService.reinstateUser(id, actor, body);
        String reason = body == null || body.reason() == null || body.reason().isBlank()
                ? "Reinstated by administrator"
                : body.reason().trim();
        // The decision has committed; tell the user about it.
        return ResponseEntity.ok(notifications.reinstated(user, reason));
    }

    @PostMapping("/users/{id}/reject")
    public ResponseEntity<AdminUserDto> rejectUser(@PathVariable("id") Long id,
                                                  @Valid @RequestBody ApproveUserRequestDto body,
                                                  HttpServletRequest request) {
        String actor = authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.rejectUser(id, actor, body));
    }

    @PostMapping("/users/{id}/suspend")
    public ResponseEntity<AdminUserDto> suspendUser(@PathVariable("id") Long id,
                                                   @Valid @RequestBody SuspendUserRequestDto body,
                                                   HttpServletRequest request) {
        String actor = authorizer.requireAdmin(request);
        AdminUserDto user = adminService.suspendUser(id, actor, body);
        // The decision has committed; tell the user about it.
        return ResponseEntity.ok(notifications.suspended(user, body.reason().trim()));
    }

    @GetMapping("/accounts")
    public ResponseEntity<List<AdminAccountDto>> getAccounts(HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.getAccounts());
    }

    @PostMapping("/accounts/{id}/status")
    public ResponseEntity<AdminAccountStatusDto> updateAccountStatus(@PathVariable("id") Long id,
                                                                     @Valid @RequestBody UpdateStatusRequestDto body,
                                                                     HttpServletRequest request) {
        String actor = authorizer.requireAdmin(request);
        AdminService.AccountStatusChange change =
                adminService.changeAccountStatus(id, body.status(), body.reason(), actor);
        // The change has committed; tell the account holder when it suspends or restores them.
        Boolean delivered = switch (change.notice()) {
            case SUSPENDED -> notifications.suspended(change.owner(), change.reason()).emailDelivered();
            case REINSTATED -> notifications.reinstated(change.owner(), change.reason()).emailDelivered();
            case NONE -> null;
        };
        return ResponseEntity.ok(AdminAccountStatusDto.of(change.account(), delivered));
    }

    @PostMapping("/accounts/{id}/balance")
    public ResponseEntity<AdminAccountDto> adjustBalance(@PathVariable("id") Long id,
                                                         @Valid @RequestBody AdjustBalanceRequestDto body,
                                                         HttpServletRequest request) {
        String actor = authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.adjustBalance(id, body, actor));
    }

    @GetMapping("/orders")
    public ResponseEntity<List<AdminOrderDto>> getOrders(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "accountId", required = false) Long accountId,
            @RequestParam(value = "limit", required = false) Integer limit,
            HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.getOrders(status, accountId, limit));
    }

    @GetMapping("/instruments")
    public ResponseEntity<List<InstrumentDto>> getInstruments(HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.getInstruments());
    }

    @PostMapping("/instruments")
    public ResponseEntity<InstrumentDto> createInstrument(@Valid @RequestBody CreateInstrumentRequestDto body,
                                                          HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createInstrument(body));
    }

    @PostMapping("/instruments/{id}/status")
    public ResponseEntity<InstrumentDto> updateInstrumentStatus(@PathVariable("id") Long id,
                                                                @Valid @RequestBody UpdateStatusRequestDto body,
                                                                HttpServletRequest request) {
        authorizer.requireAdmin(request);
        return ResponseEntity.ok(adminService.updateInstrumentStatus(id, body.status()));
    }
}
