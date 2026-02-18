package com.everrich.spendmanager.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.everrich.spendmanager.dto.AccountBalanceSummary;
import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.service.AccountBalanceService;
import com.everrich.spendmanager.service.AccountService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Controller
@RequestMapping("/balances")
public class BalanceController {

    private static final Logger log = LoggerFactory.getLogger(BalanceController.class);

    private final AccountService accountService;
    private final AccountBalanceService accountBalanceService;

    public BalanceController(AccountService accountService, AccountBalanceService accountBalanceService) {
        this.accountService = accountService;
        this.accountBalanceService = accountBalanceService;
    }

    @GetMapping
    public String viewBalances(
            @RequestParam(required = false, defaultValue = "current_month") String timeframe,
            @RequestParam(value = "startDate", required = false) String startDateStr,
            @RequestParam(value = "endDate", required = false) String endDateStr,
            @RequestParam(required = false) List<Long> accountIds,
            Model model,
            @RequestParam Map<String, String> params) {

        log.info("Viewing account balances with timeframe: {}", timeframe);
        model.addAttribute("appName", "EverRich");

        // Parse dates if provided
        LocalDate selectedStartDate = null;
        LocalDate selectedEndDate = null;
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        if (startDateStr != null && !startDateStr.trim().isEmpty()) {
            try {
                selectedStartDate = LocalDate.parse(startDateStr.trim(), formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        if (endDateStr != null && !endDateStr.trim().isEmpty()) {
            try {
                selectedEndDate = LocalDate.parse(endDateStr.trim(), formatter);
            } catch (DateTimeParseException ignored) {
            }
        }

        // Calculate the date range based on timeframe
        DateTimeRange dateRange = calculateDateTimeRange(timeframe, selectedStartDate, selectedEndDate);
        LocalDateTime startDateTime = dateRange.start();
        LocalDateTime endDateTime = dateRange.end();

        // Get all accounts
        List<Account> allAccounts = accountService.findAll();
        model.addAttribute("accounts", allAccounts);

        // Determine which accounts to show
        List<Account> selectedAccounts;
        if (accountIds == null || accountIds.isEmpty()) {
            // If no accounts selected, show all accounts
            selectedAccounts = allAccounts;
        } else {
            // Filter to selected accounts
            selectedAccounts = allAccounts.stream()
                    .filter(account -> accountIds.contains(account.getId()))
                    .toList();
        }

        // Calculate balance summaries for selected accounts
        List<AccountBalanceSummary> balanceSummaries = new ArrayList<>();
        BigDecimal totalStartingBalance = BigDecimal.ZERO;
        BigDecimal totalClosingBalance = BigDecimal.ZERO;

        for (Account account : selectedAccounts) {
            BigDecimal startingBalance = accountBalanceService.getBalanceAtOrBefore(account.getId(), startDateTime);
            BigDecimal closingBalance = accountBalanceService.getBalanceAtOrBefore(account.getId(), endDateTime);

            AccountBalanceSummary summary = new AccountBalanceSummary(
                    account.getId(),
                    account.getId() + " - " + account.getName(),
                    startingBalance,
                    closingBalance);

            balanceSummaries.add(summary);

            totalStartingBalance = totalStartingBalance.add(startingBalance);
            totalClosingBalance = totalClosingBalance.add(closingBalance);
        }

        model.addAttribute("balanceSummaries", balanceSummaries);
        model.addAttribute("totalStartingBalance", totalStartingBalance);
        model.addAttribute("totalClosingBalance", totalClosingBalance);
        model.addAttribute("totalNetChange", totalClosingBalance.subtract(totalStartingBalance));

        // Preserve filter state
        model.addAttribute("selectedTimeframe", timeframe);
        model.addAttribute("selectedStartDate", startDateStr);
        model.addAttribute("selectedEndDate", endDateStr);
        model.addAttribute("selectedAccountIds", accountIds);
        model.addAttribute("filterParams", params);

        return "balance-management";
    }

    /**
     * Internal record for date time range.
     */
    private record DateTimeRange(LocalDateTime start, LocalDateTime end) {}

    /**
     * Calculate the date/time range based on the selected timeframe.
     */
    private DateTimeRange calculateDateTimeRange(String timeframe, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start, end;
        LocalDate today = LocalDate.now();
        YearMonth ym = YearMonth.from(today);

        switch (timeframe) {
            case "entire_timeframe":
                start = LocalDateTime.of(1900, 1, 1, 0, 0, 0);
                end = LocalDateTime.of(9999, 12, 31, 23, 59, 59);
                break;
            case "last_month":
                YearMonth lastMonth = ym.minusMonths(1);
                start = lastMonth.atDay(1).atStartOfDay();
                end = lastMonth.atEndOfMonth().atTime(LocalTime.MAX);
                break;
            case "current_year":
                start = LocalDate.of(today.getYear(), 1, 1).atStartOfDay();
                end = today.atTime(LocalTime.MAX);
                break;
            case "previous_year":
                start = today.minusYears(1).with(TemporalAdjusters.firstDayOfYear()).atStartOfDay();
                end = today.minusYears(1).with(TemporalAdjusters.lastDayOfYear()).atTime(LocalTime.MAX);
                break;
            case "date_range":
                start = Optional.ofNullable(startDate).map(LocalDate::atStartOfDay).orElse(LocalDateTime.MIN);
                end = Optional.ofNullable(endDate).map(d -> d.atTime(LocalTime.MAX)).orElse(LocalDateTime.MAX);
                break;
            case "current_month":
            default:
                start = ym.atDay(1).atStartOfDay();
                end = ym.atEndOfMonth().atTime(LocalTime.MAX);
                break;
        }

        return new DateTimeRange(start, end);
    }
}
