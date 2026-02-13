package com.everrich.spendmanager.repository;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.PasswordReset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {
    
    /**
     * Find a password reset token by its token string.
     */
    Optional<PasswordReset> findByResetToken(String resetToken);
    
    /**
     * Find all password reset tokens for a user.
     */
    List<PasswordReset> findByUser(AppUser user);
    
    /**
     * Find all unused password reset tokens for a user.
     */
    List<PasswordReset> findByUserAndUsedFalse(AppUser user);
    
    /**
     * Invalidate all unused tokens for a user by marking them as used.
     */
    @Modifying
    @Query("UPDATE PasswordReset pr SET pr.used = true, pr.usedAt = :usedAt WHERE pr.user = :user AND pr.used = false")
    int invalidateAllTokensForUser(@Param("user") AppUser user, @Param("usedAt") LocalDateTime usedAt);
    
    /**
     * Delete all expired tokens (cleanup operation).
     */
    @Modifying
    @Query("DELETE FROM PasswordReset pr WHERE pr.expiresAt < :cutoffDate")
    int deleteExpiredTokens(@Param("cutoffDate") LocalDateTime cutoffDate);
}