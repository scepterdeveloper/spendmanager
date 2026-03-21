package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.AccountGroup;
import com.everrich.spendmanager.repository.AccountGroupRepository;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class AccountGroupService {

    private final AccountGroupRepository accountGroupRepository;

    public AccountGroupService(AccountGroupRepository accountGroupRepository) {
        this.accountGroupRepository = accountGroupRepository;
    }

    public List<AccountGroup> findAll() {
        List<AccountGroup> groups = accountGroupRepository.findAll();
        groups.sort((g1, g2) -> g1.getName().compareToIgnoreCase(g2.getName()));
        return groups;
    }

    public Optional<AccountGroup> findById(Long id) {
        return accountGroupRepository.findById(id);
    }

    public AccountGroup save(AccountGroup accountGroup) {
        return accountGroupRepository.save(accountGroup);
    }

    public void deleteById(Long id) {
        accountGroupRepository.deleteById(id);
    }

    public AccountGroup findByName(String name) {
        return accountGroupRepository.findByNameIgnoreCase(name);
    }
}