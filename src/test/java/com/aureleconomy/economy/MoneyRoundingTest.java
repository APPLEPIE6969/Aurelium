package com.aureleconomy.economy;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static com.aureleconomy.economy.MoneyInvariantTest.assertAmount;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One rounding policy for money (L5).
 *
 * <p>The economy layer rounded with HALF_EVEN while prices, fees, taxes and the
 * web store rounded with HALF_UP. A half cent was therefore 0.00 in a balance
 * and 0.01 in the price that produced it. The policy is now HALF_UP in
 * {@link MoneyFormat}, and the cases where the two modes disagree are pinned
 * here so a later edit cannot quietly put the split back.
 */
class MoneyRoundingTest extends MoneyInvariantTest {

    @Test
    @DisplayName("every half cent rounds up, and the table is the same for prices and balances")
    void halfCentRoundsUp() {
        // The rows where HALF_UP and HALF_EVEN disagree: a half cent with an
        // even digit in front of it. HALF_EVEN would give 0.00, 0.02 and 1.00.
        assertEquals("0.01", economy.format(new BigDecimal("0.005"), CURRENCY));
        assertEquals("0.03", economy.format(new BigDecimal("0.025"), CURRENCY));
        assertEquals("1.01", economy.format(new BigDecimal("1.005"), CURRENCY));
        assertEquals("2.68", economy.format(new BigDecimal("2.675"), CURRENCY));
        assertEquals("-0.01", economy.format(new BigDecimal("-0.005"), CURRENCY),
                "HALF_UP is away from zero");

        // Nothing surprising below the half: the old modes agreed here.
        assertEquals("0.00", economy.format(new BigDecimal("0.004"), CURRENCY));
        assertEquals("10.00", economy.format(new BigDecimal("10"), CURRENCY),
                "a whole amount still displays with the stored scale");
        assertEquals("10.00", economy.format(new BigDecimal("10.004"), CURRENCY));
    }

    @Test
    @DisplayName("a deposit of half a cent mints one cent, not nothing")
    void halfCentDepositMintsOneCent() throws Exception {
        OfflinePlayer player = player("halfcent");
        BigDecimal before = totalMoney();

        assertTrue(economy.deposit(player, new BigDecimal("0.005"), CURRENCY),
                "0.005 normalises to 0.01, which is a real deposit");

        assertAmount("half a cent deposited", new BigDecimal("0.01"), dbBalance(player));
        assertMoneyMintedBy("a deposit rounded HALF_UP", before, new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("half a cent is enough to empty a one-cent balance")
    void halfCentWithdrawalTakesTheCent() throws Exception {
        OfflinePlayer player = player("onecent");
        credit(player, "0.01");
        BigDecimal before = totalMoney();

        assertTrue(economy.withdraw(player, new BigDecimal("0.005"), CURRENCY),
                "0.005 normalises to 0.01, which this account holds");

        assertAmount("a one-cent balance after withdrawing half a cent", ZERO, dbBalance(player));
        assertMoneyBurnedBy("a withdrawal rounded HALF_UP", before, new BigDecimal("0.01"));
    }

    @Test
    @DisplayName("half a cent cannot be withdrawn from an empty account")
    void halfCentWithdrawalRefusedWhenEmpty() throws Exception {
        OfflinePlayer broke = player("empty");
        credit(broke, "0.00");
        BigDecimal before = totalMoney();

        assertFalse(economy.withdraw(broke, new BigDecimal("0.005"), CURRENCY),
                "the normalised amount is 0.01, so an empty account cannot pay it");

        assertAmount("an empty balance after a refused withdrawal", ZERO, dbBalance(broke));
        assertMoneyUnchanged("a refused half-cent withdrawal", before);
    }
}
