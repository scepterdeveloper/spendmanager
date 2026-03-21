package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.AccountGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AccountGroupRepository extends JpaRepository<AccountGroup, Long> {
    AccountGroup findByNameIgnoreCase(String name);
}