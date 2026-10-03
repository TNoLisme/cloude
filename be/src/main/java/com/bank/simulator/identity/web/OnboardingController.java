package com.bank.simulator.identity.web;

import com.bank.simulator.identity.application.OnboardingService;
import com.bank.simulator.identity.domain.AuthenticatedActor;
import com.bank.simulator.identity.infrastructure.otp.OtpChallengeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@RestController
public class OnboardingController {

    private final OnboardingService service;

    private final Clock clock;

    public OnboardingController(OnboardingService service, Clock clock) {
        this.service = service;
        this.clock = clock;
    }

    @PostMapping("/auth/register/send-otp")
    public SendOtpResponse sendRegistrationOtp(@Valid @RequestBody SendOtpRequest request) {
        OtpChallengeService.OtpIssueResult result = service.sendRegistrationOtp(request.phone());
        return new SendOtpResponse(request.phone(), result.expiresInSeconds(), "OTP sent successfully");
    }

    @PostMapping("/auth/register")
    public ResponseEntity<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
        OnboardingService.RegistrationResult result = service.register(request.phone(), request.email(), request.password(), request.fullName(), request.address(), request.otp());
        return ResponseEntity.status(HttpStatus.CREATED).body(registration(result));
    }

    @PostMapping("/auth/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               jakarta.servlet.http.HttpServletRequest httpRequest,
                                               jakarta.servlet.http.HttpServletResponse response) {
        OnboardingService.LoginResult result = service.login(request.phone(), request.password());
        response.addHeader("Set-Cookie", cookie(result.session().rawToken(), result.session().expiresAt(), httpRequest.isSecure()));
        return ResponseEntity.ok(new LoginResponse(result.accessToken(), "Bearer", 900, result.user()));
    }

    @PostMapping("/auth/recover/initiate")
    public RecoveryResponse recoverInitiate(@Valid @RequestBody RecoverInitiateRequest request) {
        OnboardingService.RecoveryResult result = service.initiateRecovery(request.identifier(), request.channel());
        return new RecoveryResponse(result.identifier(), result.channel(), result.expiresInSeconds(), result.message());
    }

    @PostMapping("/auth/recover/confirm")
    public MessageResponse recoverConfirm(@Valid @RequestBody RecoverConfirmRequest request) {
        service.confirmRecovery(request.identifier(), request.channel(), request.otp(), request.newPassword());
        return new MessageResponse("Password reset successfully and active sessions revoked");
    }

    @GetMapping("/customers/me")
    public CustomerProfile customerProfile() {
        var customer = service.currentCustomer(actor().value());
        return new CustomerProfile(customer.customerId(), customer.fullName(), customer.phone(), customer.email(), customer.pinSet(), customer.createdAt());
    }

    @PostMapping("/customers/me/pin/setup")
    public MessageResponse setupPin(@Valid @RequestBody SetupPinRequest request) {
        if (!request.pin().equals(request.confirmPin())) throw new com.bank.simulator.shared.error.ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "PIN confirmation does not match.");
        service.setupPin(actor().value(), request.pin());
        return new MessageResponse("Transaction PIN configured successfully");
    }

    @PostMapping("/customers/me/pin/change")
    public MessageResponse changePin(@Valid @RequestBody ChangePinRequest request) {
        if (!request.newPin().equals(request.confirmNewPin())) throw new com.bank.simulator.shared.error.ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "PIN confirmation does not match.");
        service.changePin(actor().value(), request.currentPin(), request.newPin());
        return new MessageResponse("Transaction PIN changed successfully");
    }

    @PostMapping("/customers/me/pin/forgot/initiate")
    public SendOtpResponse forgotPinInitiate() {
        var result = service.initiateForgotPin(actor().value());
        var customer = service.currentCustomer(actor().value());
        return new SendOtpResponse(customer.phone(), result.expiresInSeconds(), "OTP sent successfully");
    }

    @PostMapping("/customers/me/pin/forgot/confirm")
    public MessageResponse forgotPinConfirm(@Valid @RequestBody ForgotPinConfirmRequest request) {
        if (!request.newPin().equals(request.confirmNewPin())) throw new com.bank.simulator.shared.error.ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "PIN confirmation does not match.");
        service.confirmForgotPin(actor().value(), request.otp(), request.newPin());
        return new MessageResponse("Transaction PIN reset successfully");
    }

    @PostMapping("/operator/customers/send-otp")
    public SendOtpResponse operatorSendOtp(@Valid @RequestBody OperatorSendOtpRequest request) {
        var result = service.sendOperatorOtp(actor().value(), request.phone());
        return new SendOtpResponse(request.phone(), result.expiresInSeconds(), "OTP sent successfully");
    }

    @PostMapping("/operator/customers")
    public ResponseEntity<RegistrationResponse> operatorCreate(@Valid @RequestBody OperatorCreateCustomerRequest request) {
        var result = service.createAtCounter(actor().value(), request.phone(), request.email(), request.fullName(), request.address(), request.initialPassword(), request.otp());
        return ResponseEntity.status(HttpStatus.CREATED).body(registration(result));
    }
    @GetMapping("/operator/customers")
    public OnboardingService.OperatorCustomerView operatorLookup(@RequestParam(required = false) String phone,
                                                                  @RequestParam(required = false) String email) {
        return service.lookup(actor().value(), phone, email);
    }

    private AuthenticatedActor actorValue() {
        Object principal = SecurityContextHolder.getContext().getAuthentication() == null ? null : SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        if (principal instanceof AuthenticatedActor actor) return actor;
        throw new com.bank.simulator.shared.error.ApiException(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.");
    }

    private Actor actor() { return new Actor(actorValue()); }
    private record Actor(AuthenticatedActor value) {}

    private RegistrationResponse registration(OnboardingService.RegistrationResult result) {
        return new RegistrationResponse(result.customerId(), result.phone(), result.email(), result.fullName(), account(result.account()), result.createdAt());
    }

    private Account account(com.bank.simulator.identity.infrastructure.persistence.IdentityJdbcRepository.AccountRecord account) {
        return new Account(account.accountId(), account.maskedNumber(), account.accountType(), account.status(), account.balance(), account.currency(), account.openedAt());
    }

    private String cookie(String value, Instant expiresAt, boolean secure) {
        long maxAge = Math.max(0, Duration.between(clock.instant(), expiresAt).getSeconds());
        return "refresh_token=" + value + "; Max-Age=" + maxAge + "; Path=/api/v1/auth; HttpOnly" + (secure ? "; Secure" : "") + "; SameSite=Lax";
    }

    public record SendOtpRequest(@Pattern(regexp = "^0[3-9][0-9]{8}$") String phone,
                                 @Pattern(regexp = "^(REGISTRATION|OPERATOR_CREATE_CUSTOMER)$") String purpose) {}
    public record SendOtpResponse(String phone, int expiresInSeconds, String message) {}
    public record RegisterRequest(@NotBlank @Pattern(regexp = "^0[3-9][0-9]{8}$") String phone,
                                  @NotBlank @Email @Size(max = 254) String email,
                                  @NotBlank @Size(min = 12, max = 128) String password,
                                  @NotBlank @Size(min = 1, max = 120) String fullName,
                                  @Pattern(regexp = "^[0-9]{6}$") String otp,
                                  @Size(max = 300) String address) {}
    public record OperatorSendOtpRequest(@NotBlank @Pattern(regexp = "^0[3-9][0-9]{8}$") String phone) {}
    public record OperatorCreateCustomerRequest(@NotBlank @Pattern(regexp = "^0[3-9][0-9]{8}$") String phone,
                                                @NotBlank @Email @Size(max = 254) String email,
                                                @NotBlank @Size(min = 1, max = 120) String fullName,
                                                @NotBlank @Size(min = 12, max = 128) String initialPassword,
                                                @NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp,
                                                @Size(max = 300) String address) {}
    public record LoginRequest(@NotBlank @Pattern(regexp = "^0[3-9][0-9]{8}$") String phone,
                               @NotBlank @Size(min = 1, max = 128) String password) {}
    public record LoginResponse(String accessToken, String tokenType, int expiresIn, OnboardingService.UserSummary user) {}
    public record RecoverInitiateRequest(@NotBlank String identifier,
                                         @NotBlank @Pattern(regexp = "^(SMS|EMAIL)$") String channel) {}
    public record RecoveryResponse(String identifier, String channel, int expiresInSeconds, String message) {}
    public record RecoverConfirmRequest(@NotBlank String identifier,
                                        @NotBlank @Pattern(regexp = "^(SMS|EMAIL)$") String channel,
                                        @NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp,
                                        @NotBlank @Size(min = 12, max = 128) String newPassword) {}
    public record CustomerProfile(java.util.UUID customerId, String fullName, String phone, String email, boolean isPinSet, Instant createdAt) {}
    public record SetupPinRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String pin,
                                  @NotBlank @Pattern(regexp = "^[0-9]{6}$") String confirmPin) {}
    public record ChangePinRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String currentPin,
                                   @NotBlank @Pattern(regexp = "^[0-9]{6}$") String newPin,
                                   @NotBlank @Pattern(regexp = "^[0-9]{6}$") String confirmNewPin) {}
    public record ForgotPinConfirmRequest(@NotBlank @Pattern(regexp = "^[0-9]{6}$") String otp,
                                          @NotBlank @Pattern(regexp = "^[0-9]{6}$") String newPin,
                                          @NotBlank @Pattern(regexp = "^[0-9]{6}$") String confirmNewPin) {}
    public record MessageResponse(String message) {}
    public record RegistrationResponse(java.util.UUID customerId, String phone, String email, String fullName, Account account, Instant createdAt) {}
    public record Account(java.util.UUID accountId, String accountNumberMasked, String accountType, String status, String balance, String currency, Instant openedAt) {}
}
