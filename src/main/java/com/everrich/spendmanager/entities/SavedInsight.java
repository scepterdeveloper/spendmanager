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

    // Insight type constants
    public static final String TYPE_KPI = "KPI";
    public static final String TYPE_CHART = "CHART";

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
    
    // Insight type: KPI or CHART (replaces aggregateResults Boolean)
    @Column(name = "insight_type", nullable = false)
    private String insightType = TYPE_CHART;
    
    private Boolean showOnDashboard;
    
    // KPI color for top border display (hex color code, e.g., #10B981)
    private String kpiColor;
    
    // Display sequence for ordering insights on dashboard and management page (nullable for backwards compatibility)
    private Integer displaySequence;
    
    public SavedInsight(String name, String description, String timeframe) {
        this.name = name;
        this.description = description;
        this.timeframe = timeframe;
        this.intervalType = "NOT_SPECIFIED";
        this.intervalFunction = "SUM";
        this.insightType = TYPE_CHART;
    }
    
    /**
     * Helper method to check if this insight is a KPI type.
     */
    public boolean isKpi() {
        return TYPE_KPI.equals(insightType);
    }
    
    /**
     * Helper method to check if this insight is a Chart type.
     */
    public boolean isChart() {
        return TYPE_CHART.equals(insightType) || insightType == null;
    }
}
