package br.com.nh.cotacao.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "vehicle_categories")
public class VehicleCategory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private Boolean active = true;

    protected VehicleCategory() {
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Boolean getActive() { return active; }

    public void updateActive(boolean active) {
        this.active = active;
    }
}
