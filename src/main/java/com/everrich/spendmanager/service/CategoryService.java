package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.repository.CategoryRepository;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ChatClient chatClient;

    public CategoryService(CategoryRepository categoryRepository, ChatClient.Builder chatClientBuilder) {
        this.categoryRepository = categoryRepository;
        this.chatClient = chatClientBuilder.build();
        // Optionally, add logic here to create default categories on startup
        // ensureDefaultCategories();
    }

    public List<Category> findAll() {
        List<Category> categories = categoryRepository.findAll();
        categories.sort((c1, c2) -> c1.getName().compareToIgnoreCase(c2.getName()));
        return categories;
    }

    public Optional<Category> findById(Long id) {
        return categoryRepository.findById(id);
    }

    public Category save(Category category) {
        return categoryRepository.save(category);
    }

    public void deleteById(Long id) {
        categoryRepository.deleteById(id);
    }

    public Category findByName(String name) {
        return categoryRepository.findByNameIgnoreCase(name);
    }

    public List<CategorySuggestion> suggestCategories(String input) {

        String promptText = """
                You are an expert financial assistant. Your task is to recommend a list of useful financial categories and their descriptions 
                for an individual, or a family, or a small business etc. The categories should cover the aspects of income, expenses, investments,
                debts or loans etc. The granularity of the categories could be decided based on the additional user input. The common practices and 
                norms based on the country, region, age group, profession etc. could also be factored in to make the categories very real-life like 
                and ready to consume. However, try to keep the number of categories to a reasonable number (10-20) by focussing on efficient generalization 
                and grouping. Please include a category "Uncategorized" always.

                The user may choose to provide the content of a bank statement or some kind of sample data also as the input. Please intepret the 
                input and act accordingly.

                The user input is the following:
                ---
                {input}
                ---

                Respond ONLY with a JSON array of objects. Each object MUST have two fields: 'name' (String, the category name) and 'description' (String, a brief explanation for categorization).

                Example JSON structure:
                \\[
                  \\{ "name": "Rent/Mortgage", "description": "Housing payments." \\},
                  \\{ "name": "Groceries", "description": "Food purchased from supermarkets." \\}
                \\]
                """; // NOTE: Added backslashes (\) to escape the curly braces and brackets.

        PromptTemplate promptTemplate = new PromptTemplate(promptText);

        return chatClient.prompt(promptTemplate.create(Map.of("input", input)))
                .call()
                .entity(new ParameterizedTypeReference<List<CategorySuggestion>>() {
                });
    }

    // Inside CategoryService.java
    public long countAll() {
        return categoryRepository.count();
    }

    /**
     * Merges a list of CategorySuggestions into the database, avoiding duplicates.
     * 
     * @param suggestions The suggested categories to merge.
     * @return The number of new categories created.
     */
    public long mergeCategories(List<CategorySuggestion> suggestions) {
        long newCount = 0;
        List<Category> existingCategories = categoryRepository.findAll();

        for (CategorySuggestion suggestion : suggestions) {
            String suggestionName = suggestion.getName().trim();

            // Check for existing category by name (case-insensitive)
            boolean exists = existingCategories.stream()
                    .anyMatch(c -> c.getName().equalsIgnoreCase(suggestionName));

            if (!exists) {
                Category newCategory = new Category(suggestionName, suggestion.getDescription());
                categoryRepository.save(newCategory);
                newCount++;
            }
        }
        return newCount;
    }
}