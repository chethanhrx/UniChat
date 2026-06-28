package com.unichat.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "connections")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Connection {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String label;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "encrypted_api_key", nullable = false, length = 1024)
    private String encryptedApiKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private ProviderType providerType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
