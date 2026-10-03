package com.delivery.backend.modules.auth;

import jakarta.persistence.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "users") 
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "default_address")
    private String defaultAddress;

    @Column(name = "avatar_url")
    private String avatarUrl;

    @Column(name = "email_verified", columnDefinition = "boolean default false")
    private Boolean emailVerified = false;

    @Column(name = "verification_token")
    private String verificationToken;

    @Column(name = "token_expires_at")
    private LocalDateTime tokenExpiresAt;

    /**
     * Session generation for this account: how a stateless token gets revoked.
     *
     * <p>A JWT is valid to the server until it expires, with nothing stored to
     * delete when the user signs out; deleting it in the browser only stops that
     * one browser from using it. The copy that already left the server keeps
     * working. This counter fixes that: every token carries the value it was
     * minted with in its {@code tv} claim, and a token whose claim no longer
     * matches the account row is refused. Incrementing it therefore invalidates
     * every token issued so far, which is what makes logout a server-side act
     * rather than a local one.
     *
     * <p>Nullable on purpose. {@code ddl-auto: update} adds this column to a
     * {@code users} table that already holds rows, and a {@code NOT NULL} column
     * without a default cannot be added to a non-empty table. Hibernate writes 0
     * for every new row and {@link #getTokenVersion()} reads a pre-existing NULL
     * as 0, so tokens issued before the column existed stay valid until the
     * account's first logout -- the upgrade does not sign everyone out.
     */
    @Column(name = "token_version")
    private Long tokenVersion = 0L;

    public User() {}

    public User(String email, String password, Role role) {
        this.email = email;
        this.password = password;
        this.role = role;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override
    public String getPassword() { return password; }

    @Override
    public String getUsername() { return email; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public void setPassword(String password) { this.password = password; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getDefaultAddress() { return defaultAddress; }
    public void setDefaultAddress(String defaultAddress) { this.defaultAddress = defaultAddress; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }

    public Boolean getEmailVerified() { return emailVerified; }
    public void setEmailVerified(Boolean emailVerified) { this.emailVerified = emailVerified; }

    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }

    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }

    public long getTokenVersion() {
        return tokenVersion == null ? 0L : tokenVersion;
    }

    public void setTokenVersion(long tokenVersion) {
        this.tokenVersion = tokenVersion;
    }

    /** Invalidates every token issued to this account so far. */
    public void bumpTokenVersion() {
        this.tokenVersion = getTokenVersion() + 1;
    }
}