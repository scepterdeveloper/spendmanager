package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.AccountGroup;
import com.everrich.spendmanager.repository.AccountRepository;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class AccountService {

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public List<Account> findAll() {
        List<Account> accounts = accountRepository.findAll();
        accounts.sort((a1, a2) -> a1.getName().compareToIgnoreCase(a2.getName()));
        return accounts;
    }

    public Optional<Account> findById(Long id) {
        return accountRepository.findById(id);
    }

    public Account save(Account account) {
        return accountRepository.save(account);
    }

    public void deleteById(Long id) {
        accountRepository.deleteById(id);
    }

    public Account findByName(String name) {
        return accountRepository.findByNameIgnoreCase(name);
    }
    
    public List<Account> findByAccountGroup(AccountGroup accountGroup) {
        List<Account> accounts = accountRepository.findByAccountGroup(accountGroup);
        accounts.sort((a1, a2) -> a1.getName().compareToIgnoreCase(a2.getName()));
        return accounts;
    }
    
    public List<Account> findUngroupedAccounts() {
        List<Account> accounts = accountRepository.findByAccountGroupIsNull();
        accounts.sort((a1, a2) -> a1.getName().compareToIgnoreCase(a2.getName()));
        return accounts;
    }
    
    public long countByAccountGroup(AccountGroup accountGroup) {
        return accountRepository.countByAccountGroup(accountGroup);
    }
}
