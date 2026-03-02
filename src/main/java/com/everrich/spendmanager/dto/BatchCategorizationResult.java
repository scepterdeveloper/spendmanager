package com.everrich.spendmanager.dto;

import java.util.List;

/**
 * DTO for parsing batch categorization results from LLM response.
 * The LLM returns a JSON array of category assignments for multiple transactions.
 */
public class BatchCategorizationResult {
    
    /**
     * Single categorization entry mapping a transaction index to its category.
     */
    public static class CategoryAssignment {
        private int index;
        private String category;
        
        public CategoryAssignment() {}
        
        public CategoryAssignment(int index, String category) {
            this.index = index;
            this.category = category;
        }
        
        public int getIndex() {
            return index;
        }
        
        public void setIndex(int index) {
            this.index = index;
        }
        
        public String getCategory() {
            return category;
        }
        
        public void setCategory(String category) {
            this.category = category;
        }
        
        @Override
        public String toString() {
            return "CategoryAssignment{index=" + index + ", category='" + category + "'}";
        }
    }
    
    private List<CategoryAssignment> assignments;
    
    public BatchCategorizationResult() {}
    
    public BatchCategorizationResult(List<CategoryAssignment> assignments) {
        this.assignments = assignments;
    }
    
    public List<CategoryAssignment> getAssignments() {
        return assignments;
    }
    
    public void setAssignments(List<CategoryAssignment> assignments) {
        this.assignments = assignments;
    }
    
    /**
     * Gets the category for a specific transaction index.
     * @param index The 1-based transaction index
     * @return The category name or null if not found
     */
    public String getCategoryForIndex(int index) {
        if (assignments == null) {
            return null;
        }
        return assignments.stream()
                .filter(a -> a.getIndex() == index)
                .map(CategoryAssignment::getCategory)
                .findFirst()
                .orElse(null);
    }
    
    @Override
    public String toString() {
        return "BatchCategorizationResult{assignments=" + assignments + "}";
    }
}