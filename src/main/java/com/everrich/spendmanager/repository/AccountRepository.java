package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Account;
import com.everrich.spendmanager.entities.AccountGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    // You can add custom query methods here if needed
    Account findByNameIgnoreCase(String name);
    
    // Find accounts by account group
    List<Account> findByAccountGroup(AccountGroup accountGroup);
    
    // Find accounts without an account group (ungrouped)
    List<Account> findByAccountGroupIsNull();
    
    // Count accounts by account group (for deletion check)
    long countByAccountGroup(AccountGroup accountGroup);
}
