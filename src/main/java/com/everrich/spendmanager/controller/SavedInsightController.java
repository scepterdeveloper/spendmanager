package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.dto.InsightExecutionResult;
import com.everrich.spendmanager.entities.SavedInsight;
import com.everrich.spendmanager.service.SavedInsightService;
import com.everrich.spendmanager.service.CategoryService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

@Controller
@RequestMapping("/insights/manage")
public class SavedInsightController {

    private final SavedInsightService savedInsightService;
    private final CategoryService categoryService;

    public SavedInsightController(SavedInsightService savedInsightService, CategoryService categoryService) {
        this.savedInsightService = savedInsightService;
        this.categoryService = categoryService;
    }

    // List all saved insights (GET /insights/manage)
    @GetMapping
    public String listSavedInsights(Model model) {
        model.addAttribute("appName", "EverRich");
        model.addAttribute("savedInsights", savedInsightService.findAll());
        model.addAttribute("newInsight", new SavedInsight());
        model.addAttribute("categories", categoryService.findAll());
        return "insight-management";
    }

    // Handles fetching a saved insight for editing (GET /insights/manage/edit/{id})
    @GetMapping("/edit/{id}")
    public String editSavedInsight(@PathVariable Long id, Model model) {
        model.addAttribute("appName", "EverRich");
        
        // Find the insight by ID and put it into the model under the name 'newInsight'
        savedInsightService.findById(id).ifPresent(insight -> {
            model.addAttribute("newInsight", insight);
        });
        
        // Also load the list of all insights for the table view
        model.addAttribute("savedInsights", savedInsightService.findAll());
        model.addAttribute("categories", categoryService.findAll());
        
        return "insight-management";
    }    

    // Save a new or edited insight (POST /insights/manage)
    @PostMapping
    public String saveSavedInsight(@ModelAttribute SavedInsight savedInsight) {
        savedInsightService.save(savedInsight);
        return "redirect:/insights/manage";
    }

    // Delete an insight (POST /insights/manage/{id}/delete)
    @PostMapping("/{id}/delete")
    public String deleteSavedInsight(@PathVariable Long id) {
        savedInsightService.deleteById(id);
        return "redirect:/insights/manage";
    }

    // Execute a saved insight and show results page (GET /insights/manage/{id}/execute)
    @GetMapping("/{id}/execute")
    public String executeSavedInsight(@PathVariable Long id, 
                                       @RequestParam(value = "backUrl", required = false) String backUrl,
                                       Model model) {
        model.addAttribute("appName", "EverRich");
        
        try {
            InsightExecutionResult result = savedInsightService.execute(id);
            model.addAttribute("result", result);
            // Set back URL - use provided backUrl if available, otherwise default to insight management page
            model.addAttribute("backUrl", backUrl != null && !backUrl.isEmpty() ? backUrl : "/insights/manage");
            return "insight-result";
        } catch (Exception e) {
            model.addAttribute("error", "Failed to execute insight: " + e.getMessage());
            return "redirect:/insights/manage";
        }
    }

    // REST endpoint for executing insight (GET /insights/manage/{id}/execute/api)
    @GetMapping("/{id}/execute/api")
    @ResponseBody
    public ResponseEntity<InsightExecutionResult> executeSavedInsightApi(@PathVariable Long id) {
        try {
            InsightExecutionResult result = savedInsightService.execute(id);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }
}