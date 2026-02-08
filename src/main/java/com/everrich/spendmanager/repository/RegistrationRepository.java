package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.RegistrationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long> {
    
    Optional<Registration> findByRegistrationId(String registrationId);
    
    Optional<Registration> findByRegistrationIdAndStatus(String registrationId, RegistrationStatus status);
}