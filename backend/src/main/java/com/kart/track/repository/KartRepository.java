package com.kart.track.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kart.track.domain.Kart;

public interface KartRepository extends JpaRepository<Kart, Long> {

    List<Kart> findAllByOrderByKartNoAsc();

    Optional<Kart> findByKartNo(String kartNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from Kart k where k.id = :id")
    Optional<Kart> findByIdForUpdate(@Param("id") Long id);

    boolean existsByKartNo(String kartNo);

    List<Kart> findAllByStatusOrderByKartNoAsc(String status);

    long countByStatus(String status);
}
