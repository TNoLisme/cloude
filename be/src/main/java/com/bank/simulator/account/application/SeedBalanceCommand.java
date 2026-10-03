package com.bank.simulator.account.application;

public record SeedBalanceCommand(String amount, String currency, String reference) {
}
