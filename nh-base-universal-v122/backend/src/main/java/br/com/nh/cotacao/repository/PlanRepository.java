package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.MotorcycleOrigin;
import br.com.nh.cotacao.entity.Plan;
import br.com.nh.cotacao.entity.Region;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PlanRepository extends JpaRepository<Plan, Long> {
    @Query("""
            select p from Plan p
            where p.category.code = :categoryCode
              and p.region = :region
              and p.active = true
              and p.category.active = true
              and ((:motorcycleOrigin is null and p.motorcycleOrigin is null)
                   or p.motorcycleOrigin = :motorcycleOrigin)
            order by p.displayOrder asc
            """)
    List<Plan> findAvailable(
            @Param("categoryCode") String categoryCode,
            @Param("region") Region region,
            @Param("motorcycleOrigin") MotorcycleOrigin motorcycleOrigin
    );

    @Query("select p from Plan p where p.code = :code and p.active = true and p.category.active = true")
    Optional<Plan> findAvailableByCode(@Param("code") String code);

    Optional<Plan> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);

    @Query("select p from Plan p order by p.category.name asc, p.motorcycleOrigin asc, p.displayOrder asc")
    List<Plan> findAllForAdmin();
}
