package com.everrich.spendmanager.entities;

public enum TransactionCategorizationStatus {
    NOT_CATEGORIZED, 
    TO_BE_LLM_CATEGORIZED,  // Transaction parsed from statement, awaiting LLM categorization
    LLM_CATEGORIZING,       // LLM categorization in progress
    LLM_CATEGORIZED, 
    USER_CATEGORIZED
}
