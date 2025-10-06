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
        return categoryRepository.findAll();
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
                You are an expert financial assistant. Your task is to recommend a list of 10 to 15 useful financial categories and their descriptions.

                Base your suggestions on the following user input, which is either a life context or raw bank statement data:
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