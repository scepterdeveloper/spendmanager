package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.dto.InsightExecutionResult;
import com.everrich.spendmanager.entities.SavedInsight;
import com.everrich.spendmanager.service.SavedInsightService;
import com.everrich.spendmanager.service.CategoryService;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

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

    // Execute a saved insight and redirect to GET result page (for proper back navigation)
    // URL: GET /insights/manage/{id}/execute
    @GetMapping("/{id}/execute")
    public String executeSavedInsight(@PathVariable Long id, HttpSession session) {
        try {
            InsightExecutionResult result = savedInsightService.execute(id);
            
            // Store result in session with unique ID for GET retrieval
            String resultId = UUID.randomUUID().toString();
            session.setAttribute("saved_insight_result_" + resultId, result);
            
            // Redirect to GET endpoint for proper browser back navigation support
            return "redirect:/insights/manage/result/" + resultId;
            
        } catch (Exception e) {
            return "redirect:/insights/manage?error=" + e.getMessage();
        }
    }

    // GET endpoint to view cached saved insight result (supports browser back navigation)
    // URL: GET /insights/manage/result/{resultId}
    @GetMapping("/result/{resultId}")
    public String viewSavedInsightResult(@PathVariable String resultId, HttpSession session, Model model) {
        model.addAttribute("appName", "EverRich");
        
        InsightExecutionResult result = (InsightExecutionResult) session.getAttribute("saved_insight_result_" + resultId);
        
        if (result == null) {
            // Result expired or invalid - redirect back to insights management page
            return "redirect:/insights/manage?error=Result+expired.+Please+run+the+insight+again.";
        }
        
        model.addAttribute("result", result);
        // Set back URL to return to insight management page, and resultId for drill-down back navigation
        model.addAttribute("backUrl", "/insights/manage");
        model.addAttribute("resultPageUrl", "/insights/manage/result/" + resultId);
        
        return "insight-result";
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