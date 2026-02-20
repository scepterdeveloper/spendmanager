package com.everrich.spendmanager.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * Generic DTO for insight execution results.
 * Designed to be mapped to various UI chart types.
 */
public class InsightExecutionResult {
    
    public enum ResultType {
        KPI,    // Single aggregated value (1D)
        CHART   // Multiple data points (2D - table, pie chart, histogram)
    }
    
    public enum XAxisType {
        INTERVAL,   // X-axis represents time intervals (e.g., Jan, Feb, Mar)
        CATEGORY    // X-axis represents categories
    }
    
    private Long insightId;
    private String insightName;
    private String insightDescription;
    private ResultType resultType;
    
    // For KPI type (1D result)
    private Double aggregatedValue;
    
    // For CHART type (2D result)
    private List<DataPoint> dataPoints;
    private XAxisType xAxisType;
    
    // Query context for drill-down navigation
    private LocalDate queryStartDate;
    private LocalDate queryEndDate;
    private boolean drillDownEnabled;
    
    // Constructors
    public InsightExecutionResult() {}
    
    // Factory method for KPI result
    public static InsightExecutionResult createKpiResult(Long id, String name, String description, Double value) {
        InsightExecutionResult result = new InsightExecutionResult();
        result.setInsightId(id);
        result.setInsightName(name);
        result.setInsightDescription(description);
        result.setResultType(ResultType.KPI);
        result.setAggregatedValue(value);
        return result;
    }
    
    // Factory method for Chart result
    public static InsightExecutionResult createChartResult(Long id, String name, String description, 
                                                            List<DataPoint> dataPoints, XAxisType xAxisType) {
        InsightExecutionResult result = new InsightExecutionResult();
        result.setInsightId(id);
        result.setInsightName(name);
        result.setInsightDescription(description);
        result.setResultType(ResultType.CHART);
        result.setDataPoints(dataPoints);
        result.setXAxisType(xAxisType);
        return result;
    }
    
    // Inner class for data points (label-value pairs)
    public static class DataPoint {
        private String label;
        private Double value;
        private Long categoryId;    // For category-based drill-down
        private String intervalKey; // For interval-based drill-down (e.g., "2025-01")
        
        public DataPoint() {}
        
        public DataPoint(String label, Double value) {
            this.label = label;
            this.value = value;
        }
        
        public DataPoint(String label, Double value, Long categoryId) {
            this.label = label;
            this.value = value;
            this.categoryId = categoryId;
        }
        
        public DataPoint(String label, Double value, Long categoryId, String intervalKey) {
            this.label = label;
            this.value = value;
            this.categoryId = categoryId;
            this.intervalKey = intervalKey;
        }
        
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }
        public Long getCategoryId() { return categoryId; }
        public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
        public String getIntervalKey() { return intervalKey; }
        public void setIntervalKey(String intervalKey) { this.intervalKey = intervalKey; }
    }
    
    // Getters and Setters
    public Long getInsightId() { return insightId; }
    public void setInsightId(Long insightId) { this.insightId = insightId; }
    
    public String getInsightName() { return insightName; }
    public void setInsightName(String insightName) { this.insightName = insightName; }
    
    public String getInsightDescription() { return insightDescription; }
    public void setInsightDescription(String insightDescription) { this.insightDescription = insightDescription; }
    
    public ResultType getResultType() { return resultType; }
    public void setResultType(ResultType resultType) { this.resultType = resultType; }
    
    public Double getAggregatedValue() { return aggregatedValue; }
    public void setAggregatedValue(Double aggregatedValue) { this.aggregatedValue = aggregatedValue; }
    
    public List<DataPoint> getDataPoints() { return dataPoints; }
    public void setDataPoints(List<DataPoint> dataPoints) { this.dataPoints = dataPoints; }
    
    public XAxisType getXAxisType() { return xAxisType; }
    public void setXAxisType(XAxisType xAxisType) { this.xAxisType = xAxisType; }
    
    public LocalDate getQueryStartDate() { return queryStartDate; }
    public void setQueryStartDate(LocalDate queryStartDate) { this.queryStartDate = queryStartDate; }
    
    public LocalDate getQueryEndDate() { return queryEndDate; }
    public void setQueryEndDate(LocalDate queryEndDate) { this.queryEndDate = queryEndDate; }
    
    public boolean isDrillDownEnabled() { return drillDownEnabled; }
    public void setDrillDownEnabled(boolean drillDownEnabled) { this.drillDownEnabled = drillDownEnabled; }
}
