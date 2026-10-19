package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.VehicleCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VehicleCategoryRepository extends JpaRepository<VehicleCategory, Long> {
    Optional<VehicleCategory> findByCode(String code);
    List<VehicleCategory> findAllByOrderByNameAsc();
    List<VehicleCategory> findAllByActiveTrueOrderByNameAsc();
}
