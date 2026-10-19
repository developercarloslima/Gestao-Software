package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
