package com.everrich.spendmanager.entities;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity to store password reset tokens.
 * Tokens are time-limited and can only be used once.
 */
@Getter
@Setter
@Entity
@Table(name = "PASSWORD_RESET", schema = "public")
@NoArgsConstructor
public class PasswordReset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "reset_token", unique = true, nullable = false)
    private String resetToken;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
    
    @Column(name = "used_at")
    private LocalDateTime usedAt;
    
    @Column(name = "is_used", nullable = false)
    private boolean used = false;
    
    /**
     * Create a new password reset token for a user.
     * Token expires after the specified number of hours.
     * 
     * @param user the user requesting password reset
     * @param expirationHours number of hours until token expires
     */
    public PasswordReset(AppUser user, int expirationHours) {
        this.user = user;
        this.resetToken = UUID.randomUUID().toString();
        this.createdAt = LocalDateTime.now();
        this.expiresAt = this.createdAt.plusHours(expirationHours);
        this.used = false;
    }
    
    /**
     * Check if the token is still valid (not expired and not used).
     * 
     * @return true if the token can still be used
     */
    public boolean isValid() {
        return !used && LocalDateTime.now().isBefore(expiresAt);
    }
    
    /**
     * Mark the token as used.
     */
    public void markAsUsed() {
        this.used = true;
        this.usedAt = LocalDateTime.now();
    }
}