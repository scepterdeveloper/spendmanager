package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.service.AccountService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    // List all accounts (GET /accounts)
    @GetMapping
    public String listAccounts(Model model) {
        model.addAttribute("appName", "EverRich");
        model.addAttribute("accounts", accountService.findAll());
        model.addAttribute("newAccount", new Account()); // For the creation form
        return "account-management"; // Assuming you will create an account-management.html template
    }

    // Handles fetching an account for editing (GET /accounts/edit/{id})
    @GetMapping("/edit/{id}")
    public String editAccount(@PathVariable Long id, Model model) {
        model.addAttribute("appName", "EverRich");
        
        // Find the account by ID and put it into the model under the name 'newAccount'
        accountService.findById(id).ifPresent(account -> {
            model.addAttribute("newAccount", account);
        });
        
        // Also load the list of all accounts for the table view
        model.addAttribute("accounts", accountService.findAll());
        
        // We reuse the same template
        return "account-management";
    }    

    // Save a new or edited account (POST /accounts)
    @PostMapping
    public String saveAccount(@ModelAttribute Account account) {
        accountService.save(account);
        return "redirect:/accounts";
    }

    // Delete an account (POST /accounts/{id}/delete)
    @PostMapping("/{id}/delete")
    public String deleteAccount(@PathVariable Long id) {
        accountService.deleteById(id);
        return "redirect:/accounts";
    }
}
