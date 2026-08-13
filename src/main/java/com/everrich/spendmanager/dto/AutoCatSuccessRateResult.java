package com.everrich.spendmanager.dto;

/**
 * DTO to hold auto-categorization success rate information for a statement.
 * 
 * The success rate is calculated only from reviewed transactions:
 * Success Rate = (Reviewed transactions with LLM_CATEGORIZED / Total reviewed transactions) × 100
 */
public record AutoCatSuccessRateResult(
    boolean reviewComplete,     // true if all transactions are reviewed
    int reviewedCount,          // number of reviewed transactions
    int totalCount,             // total transactions in statement
    Double successRate          // null if review incomplete or N/A, otherwise 0-100
) {
    
    /**
     * Creates a result indicating the success rate is not applicable
     * (e.g., statement has no transactions or is not in COMPLETED status).
     */
    public static AutoCatSuccessRateResult notApplicable() {
        return new AutoCatSuccessRateResult(false, 0, 0, null);
    }
    
    /**
     * Creates a result indicating review is still in progress.
     * 
     * @param reviewed Number of reviewed transactions
     * @param total Total number of transactions in the statement
     */
    public static AutoCatSuccessRateResult pendingReview(int reviewed, int total) {
        return new AutoCatSuccessRateResult(false, reviewed, total, null);
    }
    
    /**
     * Creates a result with a completed review and calculated success rate.
     * 
     * @param reviewed Number of reviewed transactions (equals total)
     * @param rate The success rate percentage (0-100)
     */
    public static AutoCatSuccessRateResult complete(int reviewed, double rate) {
        return new AutoCatSuccessRateResult(true, reviewed, reviewed, rate);
    }
    
    /**
     * Returns the success rate category for color coding.
     * 
     * @return "high" (≥80%), "medium" (50-79%), "low" (<50%), or "na" if not applicable
     */
    public String getSuccessCategory() {
        if (successRate == null) {
            return reviewComplete ? "na" : "pending";
        }
        if (successRate >= 80.0) {
            return "high";
        } else if (successRate >= 50.0) {
            return "medium";
        } else {
            return "low";
        }
    }
    
    /**
     * Returns a formatted display string for the success rate.
     * 
     * @return Formatted string like "85%", "5/12 reviewed", or "N/A"
     */
    public String getDisplayValue() {
        if (totalCount == 0) {
            return "N/A";
        }
        if (!reviewComplete) {
            return reviewedCount + "/" + totalCount + " reviewed";
        }
        if (successRate != null) {
            return Math.round(successRate) + "%";
        }
        return "N/A";
    }
    
    /**
     * Returns a tooltip description for the success rate.
     */
    public String getTooltip() {
        if (totalCount == 0) {
            return "No transactions in this statement";
        }
        if (!reviewComplete) {
            return "Review in progress: " + reviewedCount + " of " + totalCount + " transactions reviewed";
        }
        if (successRate != null) {
            int llmCategorized = (int) Math.round(reviewedCount * successRate / 100.0);
            return Math.round(successRate) + "% auto-categorization success (" + llmCategorized + "/" + reviewedCount + " transactions)";
        }
        return "Success rate not available";
    }
}