package com.everrich.spendmanager.service; 
// You might put this in a dedicated DTO package later, but for now, the service package is fine.

/**
 * Data structure to hold the category suggestions returned by the LLM.
 * The fields match the required output from the LLM prompt.
 */
public class CategorySuggestion {
    private String name;
    private String description;

    // Getters and Setters (Required for Spring AI/Gson mapping)
    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getDescription() {
        return description;
    }
    public void setDescription(String description) {
        this.description = description;
    }

    // Default constructor (Required for Spring AI/Gson)
    public CategorySuggestion() {
    }

    @Override
    public String toString() {
        return "CategorySuggestion [name=" + name + ", description=" + description + "]";
    }
}