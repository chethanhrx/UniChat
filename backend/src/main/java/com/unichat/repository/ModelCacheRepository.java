package com.unichat.repository;

import com.unichat.domain.ModelCache;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ModelCacheRepository extends JpaRepository<ModelCache, Long> {
    List<ModelCache> findByConnectionId(Long connectionId);
    void deleteByConnectionId(Long connectionId);
}
