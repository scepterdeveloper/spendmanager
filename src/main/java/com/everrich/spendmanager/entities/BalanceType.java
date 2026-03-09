package com.everrich.spendmanager.entities;

/**
 * Enum representing the type of balance lookup to perform.
 * 
 * <ul>
 *   <li>{@link #DAY_BEGIN_BALANCE} - The balance BEFORE any transaction of the specified day
 *       (i.e., the previous day's closing balance). This represents the "opening balance" 
 *       at the start of the day.</li>
 *   <li>{@link #DAY_END_BALANCE} - The balance AFTER the last transaction of the specified day
 *       (i.e., the closing balance). If no transactions exist for that day, returns the most 
 *       recent balance before that day.</li>
 *   <li>{@link #INTRA_DAY_BALANCE} - The balance immediately before the specified timestamp.
 *       If time is zero/not specified, returns the most recent balance before that date.</li>
 * </ul>
 */
public enum BalanceType {
    
    /**
     * Retrieves the balance BEFORE any transaction of the specified day.
     * This is effectively the previous day's closing balance (opening balance at start of day).
     * 
     * <p>Example: If Day X has transactions, DAY_BEGIN_BALANCE returns the balance
     * as it was at the end of Day X-1, before any Day X transactions were applied.</p>
     */
    DAY_BEGIN_BALANCE,
    
    /**
     * Retrieves the balance AFTER the last transaction of the specified day.
     * This represents the closing balance at the end of the day.
     * 
     * <p>If no balance record exists for the specified day, falls back to
     * the most recent balance record before that day.</p>
     * 
     * <p>Example: If Day X has 3 transactions, DAY_END_BALANCE returns the balance
     * after the last (most recent) transaction of Day X was applied.</p>
     */
    DAY_END_BALANCE,
    
    /**
     * Retrieves the balance record immediately before/older than the specified timestamp.
     * If time is zero or not specified, falls back to the most recent balance
     * record before that date (crossing day boundaries if necessary).
     */
    INTRA_DAY_BALANCE
}