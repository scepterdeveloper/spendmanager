package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.entities.Category;
import com.everrich.spendmanager.service.CategoryService;
import com.everrich.spendmanager.service.CategorySuggestion;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    // List all categories (GET /categories)
    @GetMapping
    public String listCategories(Model model) {
        model.addAttribute("appName", "EverRich");
        model.addAttribute("categories", categoryService.findAll());
        model.addAttribute("newCategory", new Category()); // For the creation form
        return "category-management";
    }

    // NEW: Handles fetching a category for editing (GET /categories/edit/{id})
    @GetMapping("/edit/{id}")
    public String editCategory(@PathVariable Long id, Model model) {
        model.addAttribute("appName", "EverRich");
        
        // Find the category by ID and put it into the model under the name 'newCategory'
        categoryService.findById(id).ifPresent(category -> {
            model.addAttribute("newCategory", category);
        });
        
        // Also load the list of all categories for the table view
        model.addAttribute("categories", categoryService.findAll());
        
        // We reuse the same template
        return "category-management";
    }    

    @PostMapping("/api/suggest")
    @ResponseBody
    public ResponseEntity<List<CategorySuggestion>> generateSuggestions(@RequestParam("input") String input) {
        if (input == null || input.trim().isEmpty()) {
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
        
        try {
            List<CategorySuggestion> suggestions = categoryService.suggestCategories(input);
            return new ResponseEntity<>(suggestions, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            // Return an empty list or appropriate error message if LLM call fails
            return new ResponseEntity<>(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }    

    @PostMapping("/api/merge")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> mergeSuggestions(@RequestBody List<CategorySuggestion> suggestions) {
        // This method receives the *filtered* list of suggestions from the client
        if (suggestions == null || suggestions.isEmpty()) {
            return new ResponseEntity<>(Map.of("status", "error", "message", "No suggestions provided or none selected."), HttpStatus.BAD_REQUEST);
        }

        try {
            // The service layer handles the actual persistence and deduplication
            long newCount = categoryService.mergeCategories(suggestions);
            Map<String, Object> response = Map.of(
                "status", "success", 
                "message", newCount + " new categories merged successfully.",
                "newCount", newCount
            );
            return new ResponseEntity<>(response, HttpStatus.OK);
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseEntity<>(Map.of("status", "error", "message", "Failed to merge categories: " + e.getMessage()), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }    

    // Save a new or edited category (POST /categories)
    @PostMapping
    public String saveCategory(@ModelAttribute Category category) {
        categoryService.save(category);
        return "redirect:/categories";
    }

    // Delete a category (POST /categories/{id}/delete)
    @PostMapping("/{id}/delete")
    public String deleteCategory(@PathVariable Long id) {
        categoryService.deleteById(id);
        return "redirect:/categories";
    }
}