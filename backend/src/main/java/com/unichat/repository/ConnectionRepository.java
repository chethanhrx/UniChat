package com.unichat.repository;

import com.unichat.domain.Connection;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ConnectionRepository extends JpaRepository<Connection, Long> {
    List<Connection> findByUserId(Long userId);
    Optional<Connection> findByIdAndUserId(Long id, Long userId);
}
