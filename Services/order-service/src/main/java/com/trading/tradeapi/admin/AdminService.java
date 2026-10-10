package com.trading.tradeapi.admin;

import com.trading.tradeapi.dto.AdjustBalanceRequestDto;
import com.trading.tradeapi.dto.AdminAccountDto;
import com.trading.tradeapi.dto.AdminOrderDto;
import com.trading.tradeapi.dto.AdminStatsDto;
import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.dto.ApproveUserRequestDto;
import com.trading.tradeapi.dto.SuspendUserRequestDto;
import com.trading.tradeapi.dto.CreateInstrumentRequestDto;
import com.trading.tradeapi.dto.InstrumentDto;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.UserNotFoundException;
import com.trading.tradeapi.mapper.AdminMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * The administrator's decisions on users, accounts and instruments.
 *
 * <p>A user and their trading account move together: approval, rejection,
 * suspension and reinstatement change both rows in one transaction, because the
 * Trade REST API gates trading on the account and the auth service gates sign-in
 * on the user. Notification emails are sent by the controller after the
 * transaction has committed, so a slow mail server never holds a database lock and
 * a failed send never rolls back a decision that was taken.
 */
@Service
public class AdminService {

    private static final Set<String> VALID_ACCOUNT_STATUSES =
            Set.of("ACTIVE", "INACTIVE", "SUSPENDED", "CLOSED", "REJECTED");

    /** The instrument lifecycle the executor, the ETL and the schema all agree on. */
    private static final Set<String> VALID_INSTRUMENT_STATUSES = Set.of("ACTIVE", "SUSPENDED", "DELISTED");

    /** Order list size when the caller does not ask for one, and the most it may ask for. */
    static final int DEFAULT_ORDER_LIMIT = 200;
    static final int MAX_ORDER_LIMIT = 1000;

    private final AdminMapper adminMapper;

    public AdminService(AdminMapper adminMapper) {
        this.adminMapper = adminMapper;
    }

    @Transactional(readOnly = true)
    public AdminStatsDto getStats() {
        BigDecimal totalCash = adminMapper.sumCashBalance();
        return new AdminStatsDto(
                adminMapper.countUsers(),
                adminMapper.countAccounts(),
                adminMapper.countAccountsByStatus("ACTIVE"),
                adminMapper.countAccountsByStatus("INACTIVE"),
                adminMapper.countAccountsByStatus("SUSPENDED"),
                adminMapper.countOrders(),
                adminMapper.countOrdersByStatus("NEW"),
                adminMapper.countOrdersByStatus("FILLED"),
                adminMapper.countOrdersByStatus("REJECTED"),
                adminMapper.countOrdersByStatus("CANCELLED"),
                adminMapper.countInstruments(),
                totalCash == null ? BigDecimal.ZERO : totalCash
        );
    }

    /**
     * Opens an applicant's account. The balance is left untouched, at zero for a
     * new registration: funding is a separate, audited balance adjustment.
     */
    @Transactional
    public AdminUserDto approveUser(Long userId, String actorUuid, ApproveUserRequestDto request) {
        AdminUserDto user = requireUser(userId);
        if (!"client".equals(user.userType())) {
            throw new IllegalArgumentException("Only client accounts can be approved through this route");
        }
        if (!"INACTIVE".equals(user.status())) {
            throw new IllegalArgumentException(
                    "Only an INACTIVE user can be approved; use reinstate to return a suspended user");
        }
        if (user.accountId() == null) {
            throw new IllegalArgumentException("User has no trading account to approve");
        }
        String reason = request != null && request.reason() != null && !request.reason().isBlank()
                ? request.reason().trim()
                : "Approved by administrator";

        return transitionUser(user, "ACTIVE", reason, actorUuid,
                accountId -> adminMapper.updateAccountStatus(accountId, "ACTIVE"));
    }

    /** Returns a suspended user to ACTIVE. The balance is untouched: the account was frozen, not drained. */
    @Transactional
    public AdminUserDto reinstateUser(Long userId, String actorUuid, SuspendUserRequestDto request) {
        AdminUserDto user = requireUser(userId);
        if (!"SUSPENDED".equals(user.status())) {
            throw new IllegalArgumentException("Only a SUSPENDED user can be reinstated");
        }
        if (user.accountId() == null) {
            throw new IllegalArgumentException("User has no trading account to reinstate");
        }
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "Reinstated by administrator"
                : request.reason().trim();

        return transitionUser(user, "ACTIVE", reason, actorUuid,
                accountId -> adminMapper.updateAccountStatus(accountId, "ACTIVE"));
    }

    @Transactional
    public AdminUserDto rejectUser(Long userId, String actorUuid, ApproveUserRequestDto request) {
        AdminUserDto user = requireUser(userId);
        if (!"INACTIVE".equals(user.status())) {
            // Rejection is a decision on an application. Once a user is ACTIVE they
            // have trading history and open orders, so closing them out through this
            // route would strand those records; suspend is the route for a live user.
            throw new IllegalArgumentException(
                    "Only an INACTIVE user can be rejected; use suspend for an active user");
        }
        String reason = request != null && request.reason() != null ? request.reason().trim() : "";
        if (reason.isEmpty()) {
            // The reason is what the applicant is told, so it cannot be optional.
            throw new IllegalArgumentException("A rejection must state a reason");
        }
        if (user.accountId() == null) {
            throw new IllegalArgumentException("User has no trading account to reject");
        }

        return transitionUser(user, "REJECTED", reason, actorUuid,
                accountId -> adminMapper.updateAccountStatus(accountId, "REJECTED"));
    }

    /** Suspends the user and the account with it: the trade API gates on the account. */
    @Transactional
    public AdminUserDto suspendUser(Long userId, String actorUuid, SuspendUserRequestDto request) {
        AdminUserDto user = requireUser(userId);
        if (!"ACTIVE".equals(user.status())) {
            throw new IllegalArgumentException("Only an ACTIVE user can be suspended");
        }
        if (user.accountId() == null) {
            throw new IllegalArgumentException("User has no trading account to suspend");
        }
        return transitionUser(user, "SUSPENDED", request.reason().trim(), actorUuid,
                accountId -> adminMapper.updateAccountStatus(accountId, "SUSPENDED"));
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto> getPendingUsers() {
        return adminMapper.findUsersByStatus("INACTIVE");
    }

    @Transactional(readOnly = true)
    public long countPendingUsers() {
        return adminMapper.countUsersByStatus("INACTIVE");
    }

    /**
     * Moves the user and its account to the same new status in one transaction and
     * records who did it.
     */
    private AdminUserDto transitionUser(AdminUserDto user,
                                        String newStatus,
                                        String reason,
                                        String actorUuid,
                                        java.util.function.LongConsumer accountChange) {
        Long actorId = actorId(actorUuid);
        String previous = user.status();

        adminMapper.updateUserStatus(user.userId(), newStatus, actorId, reason);
        adminMapper.logStatusChange(user.userId(), previous, newStatus, reason, actorId);
        if (user.accountId() != null) {
            accountChange.accept(user.accountId());
        }

        return adminMapper.findUserById(user.userId());
    }

    private Long actorId(String actorUuid) {
        return actorUuid != null ? adminMapper.findUserIdByUuid(actorUuid) : null;
    }

    private AdminUserDto requireUser(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        AdminUserDto user = adminMapper.findUserById(userId);
        if (user == null) {
            throw new UserNotFoundException();
        }
        return user;
    }

    @Transactional(readOnly = true)
    public List<AdminUserDto> getUsers() {
        return adminMapper.findAllUsers();
    }

    @Transactional(readOnly = true)
    public List<AdminAccountDto> getAccounts() {
        return adminMapper.findAllAccounts();
    }

    /** Which notice, if any, an account status change owes the account holder. */
    public enum AccountNotice { NONE, SUSPENDED, REINSTATED }

    /** The outcome of {@link #changeAccountStatus}: what changed and whom to tell. */
    public record AccountStatusChange(AdminAccountDto account, AdminUserDto owner,
                                      AccountNotice notice, String reason) {}

    /**
     * An account status change from the Accounts page, kept in step with its owner.
     *
     * <p>Suspending an account suspends the user with it, and making a suspended
     * user's account active reinstates the user, exactly as the approval console
     * does, so the two pages cannot leave a user ACTIVE with a SUSPENDED account.
     * The caller sends the notice once this transaction has committed.
     */
    @Transactional
    public AccountStatusChange changeAccountStatus(Long accountId, String rawStatus, String rawReason,
                                                   String actorUuid) {
        AdminAccountDto account = requireAccount(accountId);
        String status = normalizeAccountStatus(rawStatus);
        String reason = rawReason == null || rawReason.isBlank() ? null : rawReason.trim();
        AdminUserDto owner = account.userId() == null ? null : adminMapper.findUserById(account.userId());
        boolean ownerActive = owner != null && "ACTIVE".equals(owner.status());
        boolean ownerSuspended = owner != null && "SUSPENDED".equals(owner.status());

        if ("SUSPENDED".equals(status) && ownerActive) {
            String why = reason != null ? reason : "Suspended by an administrator";
            AdminUserDto user = suspendUser(owner.userId(), actorUuid, new SuspendUserRequestDto(why));
            return new AccountStatusChange(adminMapper.findAccountById(accountId), user, AccountNotice.SUSPENDED, why);
        }
        if ("ACTIVE".equals(status) && ownerSuspended) {
            String why = reason != null ? reason : "Reinstated by administrator";
            AdminUserDto user = reinstateUser(owner.userId(), actorUuid, new SuspendUserRequestDto(why));
            return new AccountStatusChange(adminMapper.findAccountById(accountId), user, AccountNotice.REINSTATED, why);
        }

        boolean wasSuspended = "SUSPENDED".equals(account.status());
        AdminAccountDto updated = updateAccountStatus(accountId, status);
        // An active user whose account alone was suspended (data from before the
        // two were kept in step) is told when that account is active again.
        if (wasSuspended && "ACTIVE".equals(status) && ownerActive) {
            return new AccountStatusChange(updated, owner, AccountNotice.REINSTATED,
                    reason != null ? reason : "Reinstated by administrator");
        }
        return new AccountStatusChange(updated, owner, AccountNotice.NONE, reason);
    }

    /**
     * Sets an account's status directly. Only moves that keep the account in step
     * with its user are allowed: an account cannot trade while its user is still
     * awaiting approval, was rejected or is suspended, because the user and the
     * account are one gate. Use approve, reinstate, suspend or reject for those.
     */
    @Transactional
    public AdminAccountDto updateAccountStatus(Long accountId, String rawStatus) {
        AdminAccountDto account = requireAccount(accountId);
        String status = normalizeAccountStatus(rawStatus);
        if ("ACTIVE".equals(status)) {
            AdminUserDto owner = adminMapper.findUserById(account.userId());
            if (owner == null || !"ACTIVE".equals(owner.status())) {
                throw new IllegalArgumentException(
                        "The account holder is not ACTIVE; approve or reinstate the user instead");
            }
        }
        adminMapper.updateAccountStatus(accountId, status);
        return adminMapper.findAccountById(accountId);
    }

    /**
     * Credits or debits cash. The check and the update are one statement, so two
     * adjustments landing together cannot overdraw the account, and every
     * adjustment is recorded with its reason and the administrator who made it.
     */
    @Transactional
    public AdminAccountDto adjustBalance(Long accountId, AdjustBalanceRequestDto request, String actorUuid) {
        requireAccount(accountId);
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() == 0) {
            throw new IllegalArgumentException("Adjustment amount must be non-zero");
        }
        if (amount.scale() > 2) {
            throw new IllegalArgumentException("Adjustment amount must have at most 2 decimal places");
        }
        String reason = request.reason() == null || request.reason().isBlank()
                ? (amount.signum() > 0 ? "Deposit by administrator" : "Withdrawal by administrator")
                : request.reason().trim();

        if (adminMapper.addBalanceIfNotOverdrawn(accountId, amount) == 0) {
            throw new IllegalArgumentException("Adjustment would overdraw the account balance");
        }
        adminMapper.logBalanceAdjustment(accountId, amount, reason, actorId(actorUuid));
        return adminMapper.findAccountById(accountId);
    }

    @Transactional(readOnly = true)
    public List<AdminOrderDto> getOrders(String status, Long accountId, Integer limit) {
        int bounded = limit == null || limit <= 0 ? DEFAULT_ORDER_LIMIT : Math.min(limit, MAX_ORDER_LIMIT);
        return adminMapper.findAllOrders(status, accountId, bounded);
    }

    @Transactional(readOnly = true)
    public List<InstrumentDto> getInstruments() {
        return adminMapper.findAllInstruments();
    }

    @Transactional
    public InstrumentDto createInstrument(CreateInstrumentRequestDto request) {
        String symbol = request.symbol().trim().toUpperCase();
        String ticker = request.ticker() != null && !request.ticker().isBlank()
                ? request.ticker().trim().toUpperCase()
                : symbol;
        String assetClass = request.assetClass() != null && !request.assetClass().isBlank()
                ? request.assetClass().trim().toUpperCase()
                : "EQUITY";
        String quoteCurrency = request.quoteCurrency() != null && !request.quoteCurrency().isBlank()
                ? request.quoteCurrency().trim().toUpperCase()
                : "USD";
        String status = request.status() != null && !request.status().isBlank()
                ? normalizeInstrumentStatus(request.status())
                : "ACTIVE";
        try {
            adminMapper.insertInstrument(symbol, ticker, assetClass, quoteCurrency, status);
        } catch (DuplicateKeyException e) {
            throw new IllegalArgumentException("Instrument " + symbol + " already exists");
        }
        return adminMapper.findAllInstruments().stream()
                .filter(i -> symbol.equals(i.symbol()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Instrument was not persisted"));
    }

    @Transactional
    public InstrumentDto updateInstrumentStatus(Long instrumentId, String rawStatus) {
        String status = normalizeInstrumentStatus(rawStatus);
        int rows = adminMapper.updateInstrumentStatus(instrumentId, status);
        if (rows == 0) {
            throw new IllegalArgumentException("Instrument not found");
        }
        return adminMapper.findAllInstruments().stream()
                .filter(i -> instrumentId.equals(i.instrumentId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Instrument was not updated"));
    }

    private AdminAccountDto requireAccount(Long accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        AdminAccountDto account = adminMapper.findAccountById(accountId);
        if (account == null) {
            throw new AccountNotFoundException();
        }
        return account;
    }

    private String normalizeInstrumentStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        String status = rawStatus.trim().toUpperCase();
        if (!VALID_INSTRUMENT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported instrument status: " + rawStatus);
        }
        return status;
    }

        private String normalizeAccountStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        String status = rawStatus.trim().toUpperCase();
        if (!VALID_ACCOUNT_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported account status: " + rawStatus);
        }
        return status;
    }
}
