package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    // You can add custom query methods here if needed
    Account findByNameIgnoreCase(String name);
}
