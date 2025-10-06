package com.everrich.spendmanager.service; 

import java.time.LocalDate;

/**
 * Utility class to hold LocalDate constants accessible by JPQL.
 * Using constants here avoids issues where LocalDate.MIN/MAX 
 * fall outside the database's supported date range.
 */
public final class DateUtils {
    // LocalDate.MIN is often too far in the past for many SQL databases.
    // Using an arbitrary, reasonable minimum date.
    public static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    
    // LocalDate.MAX is too far in the future. Using a reasonable maximum.
    // We'll rely on the service to pass LocalDate.MAX for "no end date".
    public static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31); 

    private DateUtils() {
        // Private constructor to prevent instantiation
    }
}