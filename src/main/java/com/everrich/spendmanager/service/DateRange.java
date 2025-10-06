package com.everrich.spendmanager.service;

import java.time.LocalDate;

class DateRange {
    private final LocalDate start;
    private final LocalDate end;

    public DateRange(LocalDate start, LocalDate end) {
        this.start = start;
        this.end = end;
    }
    public LocalDate getStart() { return start; }
    public LocalDate getEnd() { return end; }
}