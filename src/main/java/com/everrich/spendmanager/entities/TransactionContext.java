package com.everrich.spendmanager.entities;

// Note: Create a new package/file for this simple record
public record TransactionContext(
    String searchKey,        // The concatenated text (e.g., "PAYPAL EUROPE MINUS")
    String description,
    String operation,
    String category
) {}