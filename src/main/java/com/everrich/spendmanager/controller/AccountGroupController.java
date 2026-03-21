package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.entities.AccountGroup;
import com.everrich.spendmanager.service.AccountGroupService;
import com.everrich.spendmanager.service.AccountService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/account-groups")
public class AccountGroupController {

    private final AccountGroupService accountGroupService;
    private final AccountService accountService;

    public AccountGroupController(AccountGroupService accountGroupService, AccountService accountService) {
        this.accountGroupService = accountGroupService;
        this.accountService = accountService;
    }

    // List all account groups (GET /account-groups)
    @GetMapping
    public String listAccountGroups(Model model) {
        model.addAttribute("appName", "EverRich");
        model.addAttribute("accountGroups", accountGroupService.findAll());
        model.addAttribute("newAccountGroup", new AccountGroup());
        return "account-group-management";
    }

    // Handles fetching an account group for editing (GET /account-groups/edit/{id})
    @GetMapping("/edit/{id}")
    public String editAccountGroup(@PathVariable Long id, Model model) {
        model.addAttribute("appName", "EverRich");
        
        // Find the account group by ID and put it into the model
        accountGroupService.findById(id).ifPresent(accountGroup -> {
            model.addAttribute("newAccountGroup", accountGroup);
        });
        
        // Also load the list of all account groups for the table view
        model.addAttribute("accountGroups", accountGroupService.findAll());
        
        return "account-group-management";
    }    

    // Save a new or edited account group (POST /account-groups)
    @PostMapping
    public String saveAccountGroup(@ModelAttribute AccountGroup accountGroup, RedirectAttributes redirectAttributes) {
        accountGroupService.save(accountGroup);
        redirectAttributes.addFlashAttribute("message", "Account group saved successfully.");
        redirectAttributes.addFlashAttribute("messageType", "success");
        return "redirect:/account-groups";
    }

    // Delete an account group (POST /account-groups/{id}/delete)
    @PostMapping("/{id}/delete")
    public String deleteAccountGroup(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        // Check if any accounts are assigned to this group
        AccountGroup accountGroup = accountGroupService.findById(id).orElse(null);
        
        if (accountGroup == null) {
            redirectAttributes.addFlashAttribute("message", "Account group not found.");
            redirectAttributes.addFlashAttribute("messageType", "error");
            return "redirect:/account-groups";
        }
        
        long accountCount = accountService.countByAccountGroup(accountGroup);
        
        if (accountCount > 0) {
            redirectAttributes.addFlashAttribute("message", 
                "Cannot delete account group '" + accountGroup.getName() + "'. " +
                accountCount + " account(s) are assigned to this group. " +
                "Please reassign or remove accounts from this group before deleting.");
            redirectAttributes.addFlashAttribute("messageType", "error");
            return "redirect:/account-groups";
        }
        
        accountGroupService.deleteById(id);
        redirectAttributes.addFlashAttribute("message", "Account group deleted successfully.");
        redirectAttributes.addFlashAttribute("messageType", "success");
        return "redirect:/account-groups";
    }
}