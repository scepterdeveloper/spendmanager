package com.everrich.spendmanager.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.everrich.spendmanager.service.SavedInsightService;

@Controller
@RequestMapping("/dashboard")
public class DashboardController {

    private final SavedInsightService savedInsightService;
    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    public DashboardController(SavedInsightService savedInsightService) {
        this.savedInsightService = savedInsightService;
    }

    @GetMapping
    public String prepareDashboard(Model model)    {

        model.addAttribute("appName", "EverRich");
        model.addAttribute("dashBoardKPIs", savedInsightService.getDashBoardKPIs());
        //log.info("Fetched Dashboard KPIs: " + savedInsightService.getDashBoardKPIs().size());

        model.addAttribute("dashBoardCharts", savedInsightService.getDashBoardCharts());
        //log.info("Fetched Dashboard Charts: " + savedInsightService.getDashBoardCharts().size());


        return "dashboard";
    }
}
