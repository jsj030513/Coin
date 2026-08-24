package com.coin.arbitrage.web;

import com.coin.arbitrage.persistence.ExternalFeeRepository;
import com.coin.arbitrage.persistence.PrincipalDepositRepository;
import com.coin.arbitrage.service.ExternalFeeService;
import com.coin.arbitrage.service.PrincipalDepositService;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/capital")
public class CapitalRecordController {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final BigDecimal BANK_TRANSFER_FEE_KRW = BigDecimal.valueOf(1_000);
    private final PrincipalDepositService principalDeposits;
    private final ExternalFeeService externalFees;
    private final PrincipalDepositRepository depositRepository;
    private final ExternalFeeRepository feeRepository;

    public CapitalRecordController(PrincipalDepositService principalDeposits,
                                   ExternalFeeService externalFees,
                                   PrincipalDepositRepository depositRepository,
                                   ExternalFeeRepository feeRepository) {
        this.principalDeposits = principalDeposits;
        this.externalFees = externalFees;
        this.depositRepository = depositRepository;
        this.feeRepository = feeRepository;
    }

    @GetMapping
    public Summary summary(Principal principal) {
        String username = principal.getName();
        LocalDate today = LocalDate.now(SEOUL);
        return new Summary(
                depositRepository.sumByUsername(username),
                depositRepository.sumByUsernameAndDepositDate(username, today),
                feeRepository.sumByUsername(username),
                feeRepository.sumByUsernameAndFeeDate(username, today),
                feeRepository.countByUserUsername(username),
                feeRepository.countByUserUsernameAndFeeDate(username, today),
                BANK_TRANSFER_FEE_KRW
        );
    }

    @PostMapping("/deposits")
    public Summary recordDeposit(Principal principal, @RequestBody AmountRequest request) {
        principalDeposits.recordForUsername(principal.getName(), request.amountKrw());
        return summary(principal);
    }

    @PostMapping("/fees")
    public Summary recordFee(Principal principal, @RequestBody AmountRequest request) {
        externalFees.recordForUsername(principal.getName(), request.amountKrw());
        return summary(principal);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("error", error.getMessage()));
    }

    public record AmountRequest(long amountKrw) { }
    public record Summary(BigDecimal principalKrw, BigDecimal todayPrincipalKrw,
                          BigDecimal externalFeeKrw, BigDecimal todayExternalFeeKrw,
                          long externalFeeCount, long todayExternalFeeCount,
                          BigDecimal bankTransferFeeKrw) { }
}
