package com.aureleconomy.economy;

import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.DisplayName;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static com.aureleconomy.economy.MoneyInvariantTest.assertAmount;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

/**
 * Covers the money API reporting what actually happened (C3-core).
 *
 * <p>{@code withdraw} and {@code withdrawIfSufficient} used to decide affordability
 * from the balance cache, queue the write asynchronously, and return before it ran.
 * A caller could therefore be told the money was taken when the guarded
 * {@code UPDATE ... AND balance >= ?} had matched nothing and the withdrawal had
 * never happened. That is not a narrow race: a web purchase and an in-game
 * purchase touching the same account at the same time is exactly when the cache
 * and the database disagree, and it is the condition the rest of this section is
 * about.
 *
 * <p>Each test measures the whole ledger through {@link MoneyInvariantTest}. A
 * standalone withdrawal is a burn rather than a movement, because nothing in the
 * ledger owes that player anything, so those tests say so explicitly with
 * {@code assertMoneyBurnedBy} instead of pretending the sum held still.
 */
class EconomyMoneyApiTest extends MoneyInvariantTest {

    // --- withdraw -----------------------------------------------------------

    @Test
    @DisplayName("withdrawing with sufficient funds reports true and debits exactly that much")
    void withdrawTakesTheMoney() throws Exception {
        OfflinePlayer player = player("payer");
        credit(player, "100.00");
        BigDecimal before = totalMoney();

        assertTrue(economy.withdraw(player, new BigDecimal("30.00"), CURRENCY),
                "a withdrawal the database accepted must report true");

        assertAmount("balance after withdrawing 30", new BigDecimal("70.00"), dbBalance(player));
        assertMoneyBurnedBy("a straightforward withdrawal", before, new BigDecimal("30.00"));
    }

    @Test
    @DisplayName("withdrawing more than is held reports false and changes nothing")
    void withdrawRefusesWhenBroke() throws Exception {
        OfflinePlayer player = player("broke");
        credit(player, "10.00");
        BigDecimal before = totalMoney();

        assertFalse(economy.withdraw(player, new BigDecimal("10.01"), CURRENCY));
        assertFalse(economy.withdrawIfSufficient(player, new BigDecimal("10.01"), CURRENCY));

        assertAmount("balance after a refused withdrawal", new BigDecimal("10.00"), dbBalance(player));
        assertMoneyUnchanged("a refused withdrawal", before);
    }

    @Test
    @DisplayName("withdrawing from an account with no row at all reports false")
    void withdrawRefusesWhenNoRow() throws Exception {
        OfflinePlayer player = player("ghost");
        BigDecimal before = totalMoney();

        assertFalse(economy.withdraw(player, new BigDecimal("1.00"), CURRENCY),
                "there is no balance to take, so this must be refused");

        assertMoneyUnchanged("a withdrawal against a missing account", before);
    }

    @Test
    @DisplayName("withdrawing nothing or a negative amount reports false and writes nothing")
    void withdrawRejectsNonPositiveAmounts() throws Exception {
        OfflinePlayer player = player("idle");
        credit(player, "50.00");
        BigDecimal before = totalMoney();

        assertFalse(economy.withdraw(player, BigDecimal.ZERO, CURRENCY));
        assertFalse(economy.withdraw(player, new BigDecimal("-25.00"), CURRENCY));
        assertFalse(economy.withdrawIfSufficient(player, BigDecimal.ZERO, CURRENCY));
        assertFalse(economy.withdraw(player, null, CURRENCY));

        assertAmount("balance after a non-positive withdrawal", new BigDecimal("50.00"), dbBalance(player));
        assertMoneyUnchanged("a non-positive withdrawal", before);
    }

    @Test
    @DisplayName("withdrawIfSufficient refuses rather than letting a balance go negative")
    void withdrawIfSufficientNeverGoesNegative() throws Exception {
        OfflinePlayer player = player("exact");
        credit(player, "5.00");
        BigDecimal before = totalMoney();

        assertTrue(economy.withdrawIfSufficient(player, new BigDecimal("5.00"), CURRENCY));
        assertAmount("balance after taking everything", ZERO, dbBalance(player));
        assertFalse(economy.withdrawIfSufficient(player, new BigDecimal("0.01"), CURRENCY));

        assertMoneyBurnedBy("taking a balance down to exactly zero and stopping",
                before, new BigDecimal("5.00"));
    }

    // --- the case that must fail before the fix -----------------------------

    @Test
    @DisplayName("a stale cache claiming affordability does not produce a phantom withdrawal")
    void cacheDivergenceIsRefused() throws Exception {
        OfflinePlayer player = player("divergent");
        credit(player, "100.00");

        // Prime the cache by reading through the API, which caches what it reads.
        assertAmount("cached balance before the ledger moves",
                new BigDecimal("100.00"), economy.getBalance(player, CURRENCY));

        // Make the database disagree, the way a racing web purchase would. Written
        // straight to the ledger so the cache is left behind.
        setBehindTheApi(player, "0.00");

        assertAmount("cached balance, still stale on purpose",
                new BigDecimal("100.00"), economy.getBalance(player, CURRENCY));
        BigDecimal before = totalMoney();

        assertFalse(economy.withdrawIfSufficient(player, new BigDecimal("50.00"), CURRENCY),
                "a withdrawal the database never accepted must not be reported as taken");
        assertFalse(economy.withdraw(player, new BigDecimal("50.00"), CURRENCY),
                "withdraw must refuse on the same grounds");

        assertAmount("balance must still be zero, not negative", ZERO, dbBalance(player));
        assertMoneyUnchanged("a refused withdrawal against a stale cache", before);
    }

    @Test
    @DisplayName("a stale cache in the other direction does not block a legitimate withdrawal")
    void cacheDivergenceDoesNotOverRefuse() throws Exception {
        OfflinePlayer player = player("optimistic");
        credit(player, "0.00");
        assertAmount("cached balance before the ledger moves", ZERO, economy.getBalance(player, CURRENCY));

        setBehindTheApi(player, "75.00");
        BigDecimal before = totalMoney();

        assertTrue(economy.withdraw(player, new BigDecimal("25.00"), CURRENCY),
                "the database can pay, so the withdrawal must go through whatever the cache says");

        assertAmount("balance after the withdrawal", new BigDecimal("50.00"), dbBalance(player));
        assertMoneyBurnedBy("a withdrawal the database could fund", before, new BigDecimal("25.00"));
    }

    // --- deposit ------------------------------------------------------------

    @Test
    @DisplayName("depositing credits exactly that much and reports true")
    void depositCreditsExactly() throws Exception {
        OfflinePlayer player = player("payee");
        credit(player, "5.00");
        BigDecimal before = totalMoney();

        assertTrue(economy.deposit(player, new BigDecimal("12.34"), CURRENCY));

        assertAmount("balance after depositing 12.34", new BigDecimal("17.34"), dbBalance(player));
        assertMoneyMintedBy("a deposit", before, new BigDecimal("12.34"));
    }

    @Test
    @DisplayName("depositing creates a balance for an account that had none")
    void depositCreatesTheRow() throws Exception {
        OfflinePlayer player = player("newcomer");
        BigDecimal before = totalMoney();

        assertTrue(economy.deposit(player, new BigDecimal("40.00"), CURRENCY));

        assertAmount("balance of a brand new account", new BigDecimal("40.00"), dbBalance(player));
        assertMoneyMintedBy("a deposit into a new account", before, new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("depositing nothing or a negative amount reports false and writes nothing")
    void depositRejectsNonPositiveAmounts() throws Exception {
        OfflinePlayer player = player("deaf");
        credit(player, "20.00");
        BigDecimal before = totalMoney();

        assertFalse(economy.deposit(player, BigDecimal.ZERO, CURRENCY));
        assertFalse(economy.deposit(player, new BigDecimal("-1.00"), CURRENCY));
        assertFalse(economy.deposit(player, null, CURRENCY));

        assertAmount("balance after a non-positive deposit", new BigDecimal("20.00"), dbBalance(player));
        assertMoneyUnchanged("a non-positive deposit", before);
    }

    @Test
    @DisplayName("a deposit does not consume an existing offline earning")
    void depositAndOfflineEarningAreBothCounted() throws Exception {
        OfflinePlayer player = player("both");
        credit(player, "10.00");
        recordOfflineEarning(player, "6.00");
        BigDecimal before = totalMoney();

        assertTrue(economy.deposit(player, new BigDecimal("10.00"), CURRENCY));

        assertAmount("player balance", new BigDecimal("20.00"), dbBalance(player));
        assertAmount("offline earning still owed",
                new BigDecimal("6.00"),
                sum("SELECT COALESCE(SUM(amount), 0) FROM offline_earnings"));
        assertMoneyMintedBy("a deposit alongside an offline earning", before, new BigDecimal("10.00"));
    }

    // --- what the operator is told ---------------------------------------------

@Test
    @DisplayName("a refused withdrawal is logged with the phrase the RCON suite greps for")
    void refusalIsLoggedWithTheExpectedPhrase() throws Exception {
        OfflinePlayer player = player("logged");
        credit(player, "1.00");

        assertFalse(economy.withdraw(player, new BigDecimal("500.00"), CURRENCY));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logger).warn(message.capture());
        assertTrue(message.getValue().contains("Insufficient funds for withdraw"),
                "test_rcon.py greps the server log for this phrase: " + message.getValue());
        assertTrue(message.getValue().contains("logged"),
                "the log should name the player: " + message.getValue());
    }

    @Test
    @DisplayName("the refusal logs the database balance, not the stale cached one")
    void refusalLogsTheDatabaseBalance() throws Exception {
        OfflinePlayer player = player("divergent-log");
        credit(player, "100.00");
        assertAmount("cache primed", new BigDecimal("100.00"), economy.getBalance(player, CURRENCY));

        // Database says no, cache still says yes. The refusal has to be logged with
        // the number the request was actually judged against.
        setBehindTheApi(player, "0.00");

        assertFalse(economy.withdraw(player, new BigDecimal("50.00"), CURRENCY));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logger).warn(message.capture());
        String text = message.getValue();
        assertTrue(text.contains("Insufficient funds for withdraw"), text);
        assertTrue(text.contains("the database holds 0"),
                "the logged balance must be the database's 0, not the cache's 100: " + text);
    }

    // --- transfer -----------------------------------------------------------

    @Test
    @DisplayName("a transfer moves exactly the amount and reports true")
    void transferMovesBothLegs() throws Exception {
        OfflinePlayer from = player("from");
        OfflinePlayer to = player("to");
        credit(from, "100.00");
        credit(to, "7.00");
        BigDecimal before = totalMoney();

        assertTrue(economy.transfer(from, to, new BigDecimal("30.00"), CURRENCY));

        assertAmount("payer after transferring 30", new BigDecimal("70.00"), dbBalance(from));
        assertAmount("payee after receiving 30", new BigDecimal("37.00"), dbBalance(to));
        assertMoneyUnchanged("a successful transfer", before);
    }

    @Test
    @DisplayName("a transfer the payer cannot cover leaves both accounts untouched")
    void transferIsAtomicOnInsufficientFunds() throws Exception {
        OfflinePlayer from = player("poor");
        OfflinePlayer to = player("rich");
        credit(from, "10.00");
        credit(to, "0.00");
        BigDecimal before = totalMoney();

        assertFalse(economy.transfer(from, to, new BigDecimal("10.01"), CURRENCY));

        assertAmount("payer must not be debited", new BigDecimal("10.00"), dbBalance(from));
        assertAmount("payee must not be credited", ZERO, dbBalance(to));
        assertMoneyUnchanged("a refused transfer", before);
    }

    @Test
    @DisplayName("a failure on the credit leg rolls the debit back")
    void transferRollsBackTheDebit() throws Exception {
        OfflinePlayer from = player("from");
        OfflinePlayer to = player("blocked");
        credit(from, "100.00");
        BigDecimal before = totalMoney();

        // Make the payee's credit fail the way a broken database would, after the
        // debit has already been applied inside the same transaction.
        try (var st = connection.createStatement()) {
            st.execute("CREATE TRIGGER block_credit BEFORE INSERT ON player_balances "
                    + "WHEN NEW.uuid = '" + to.getUniqueId() + "' "
                    + "BEGIN SELECT RAISE(ABORT, 'injected credit failure'); END;");
        }

        assertFalse(economy.transfer(from, to, new BigDecimal("30.00"), CURRENCY),
                "a transfer whose credit leg failed must report false");

        assertAmount("the debit must have been rolled back", new BigDecimal("100.00"), dbBalance(from));
        assertMoneyUnchanged("a transfer that failed on the credit leg", before);
    }

    @Test
    @DisplayName("a transfer to yourself is a successful no-op")
    void transferToSelfChangesNothing() throws Exception {
        OfflinePlayer player = player("selfish");
        credit(player, "60.00");
        BigDecimal before = totalMoney();

        assertTrue(economy.transfer(player, player, new BigDecimal("25.00"), CURRENCY));

        assertAmount("balance after transferring to self", new BigDecimal("60.00"), dbBalance(player));
        assertMoneyUnchanged("a transfer to self", before);
    }

    @Test
    @DisplayName("a transfer of nothing or a negative amount reports false and writes nothing")
    void transferRejectsNonPositiveAmounts() throws Exception {
        OfflinePlayer from = player("from");
        OfflinePlayer to = player("to");
        credit(from, "40.00");
        credit(to, "0.00");
        BigDecimal before = totalMoney();

        assertFalse(economy.transfer(from, to, BigDecimal.ZERO, CURRENCY));
        assertFalse(economy.transfer(from, to, new BigDecimal("-5.00"), CURRENCY));
        assertFalse(economy.transfer(from, to, null, CURRENCY));

        assertAmount("payer after a non-positive transfer", new BigDecimal("40.00"), dbBalance(from));
        assertAmount("payee after a non-positive transfer", ZERO, dbBalance(to));
        assertMoneyUnchanged("a non-positive transfer", before);
    }

    @Test
    @DisplayName("a transfer out of a currency the payer does not hold is refused")
    void transferIsRefusedForAnUnheldCurrency() throws Exception {
        OfflinePlayer from = player("from");
        OfflinePlayer to = player("to");
        credit(from, CURRENCY, "100.00");
        BigDecimal before = totalMoney();

        assertFalse(economy.transfer(from, to, new BigDecimal("1.00"), "gems"));

        assertAmount("payer untouched", new BigDecimal("100.00"), dbBalance(from, CURRENCY));
        assertAmount("payee must not be credited a currency nobody used",
                ZERO, dbBalance(to, "gems"));
        assertMoneyUnchanged("a transfer in an unheld currency", before);
    }

    @Test
    @DisplayName("a transfer out of order escrow moves value without creating any")
    void transferConservesEscrowAndBalancesTogether() throws Exception {
        OfflinePlayer seller = player("seller");
        OfflinePlayer buyer = player("buyer");
        credit(seller, "50.00");
        placeBuyOrder("10.00", 4, 1);
        BigDecimal before = totalMoney();

        // Escrow holds 3 x 10.00. Transfer what a single fill would release.
        assertTrue(economy.transfer(seller, buyer, new BigDecimal("10.00"), CURRENCY));

        assertAmount("seller", new BigDecimal("40.00"), dbBalance(seller));
        assertAmount("buyer", new BigDecimal("10.00"), dbBalance(buyer));
        assertAmount("escrow is still held against the order",
                new BigDecimal("30.00"), orderEscrow());
        assertMoneyUnchanged("a transfer alongside held escrow", before);
    }

    // --- concurrency --------------------------------------------------------

    @Test
    @DisplayName("concurrent transfers out of one account conserve the total")
    void concurrentTransfersConserveTheTotal() throws Exception {
        OfflinePlayer from = player("contested");
        credit(from, "1000.00");
        BigDecimal before = totalMoney();
        int threads = 4;
        int each = 100;

        CountDownLatch startLine = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        List<OfflinePlayer> claimants = new ArrayList<>();
        boolean[] outcomes = new boolean[threads];

        for (int i = 0; i < threads; i++) {
            OfflinePlayer to = player("claimer" + i);
            final int index = i;
            claimants.add(to);
            Thread worker = new Thread(() -> {
                try {
                    startLine.await();
                    outcomes[index] = economy.transfer(from, to,
                            new BigDecimal(String.valueOf(each)), CURRENCY);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(worker);
            worker.start();
        }
        startLine.countDown();
        for (Thread worker : workers) {
            worker.join(15_000);
        }

        int succeeded = 0;
        BigDecimal claimed = ZERO;
        for (int i = 0; i < threads; i++) {
            if (outcomes[i]) {
                succeeded++;
                claimed = claimed.add(dbBalance(claimants.get(i)));
            } else {
                assertAmount("a refused transfer must leave nothing behind for its payee",
                        ZERO, dbBalance(claimants.get(i)));
            }
        }

        // Whether all four succeed or some are refused, every accepted amount has to
        // arrive exactly once and the payer has to hold exactly what was left. A
        // surplus in either direction is a mint or a burn.
        assertAmount("every accepted transfer must have arrived exactly once",
                new BigDecimal(String.valueOf(succeeded * each)), claimed);
        assertAmount("the payer must hold exactly what was not transferred",
                new BigDecimal(String.valueOf(1000 - succeeded * each)), dbBalance(from));
        assertMoneyUnchanged("concurrent transfers out of one account", before);
    }
}