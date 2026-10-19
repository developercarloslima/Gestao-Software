package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.PromotionalMotorcyclePrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PromotionalMotorcyclePriceRepository extends JpaRepository<PromotionalMotorcyclePrice, Long> {
    List<PromotionalMotorcyclePrice> findAllByOrderBySortOrderAsc();
    Optional<PromotionalMotorcyclePrice> findByTierCode(String tierCode);
}
