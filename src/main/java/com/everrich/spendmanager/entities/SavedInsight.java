package com.everrich.spendmanager.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Setter
@Entity
@Table(name = "SAVED_INSIGHT")
@NoArgsConstructor
public class SavedInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private String name;
    
    private String description;
    
    // Filter parameters matching insights.html
    @Column(nullable = false)
    private String timeframe; // THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR, ENTIRE_TIMEFRAME, DATE_RANGE
    
    private LocalDate startDate; // Used when timeframe = DATE_RANGE
    
    private LocalDate endDate; // Used when timeframe = DATE_RANGE
    
    @Column(name = "interval_type")
    private String intervalType; // NOT_SPECIFIED, MONTHLY
    
    private String intervalFunction; // SUM
    
    @Column(length = 1000)
    private String categoryIds; // Comma-separated category IDs
    
    private Boolean aggregateResults;
    
    public SavedInsight(String name, String description, String timeframe) {
        this.name = name;
        this.description = description;
        this.timeframe = timeframe;
        this.intervalType = "NOT_SPECIFIED";
        this.intervalFunction = "SUM";
        this.aggregateResults = false;
    }
}
