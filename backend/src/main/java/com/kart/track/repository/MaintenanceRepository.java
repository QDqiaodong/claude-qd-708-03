package com.kart.track.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kart.track.domain.Maintenance;

import jakarta.persistence.LockModeType;

public interface MaintenanceRepository extends JpaRepository<Maintenance, Long> {

    List<Maintenance> findAllByOrderByStartTimeDesc();

    Optional<Maintenance> findByOrderNo(String orderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Maintenance m where m.id = :id")
    Optional<Maintenance> findByIdForUpdate(@Param("id") Long id);

    boolean existsByOrderNo(String orderNo);

    List<Maintenance> findAllByKartIdOrderByStartTimeDesc(Long kartId);

    long countByKartIdAndStatus(Long kartId, String status);

    long countByKartIdAndStatusIn(Long kartId, Collection<String> statuses);

    long countByKartIdAndStatusAndIdNot(Long kartId, String status, Long excludedId);

    List<Maintenance> findAllByStatusOrderByStartTimeAsc(String status);

    long countByStatus(String status);
}
