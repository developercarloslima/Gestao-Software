package br.com.nh.cotacao.service;

import br.com.nh.cotacao.platform.GestaoTenantConfigService;
import br.com.nh.cotacao.repository.PortalUserRepository;
import br.com.nh.cotacao.security.PortalRole;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.text.Normalizer;
import java.util.*;

@Service
public class EmployeeExcelImportService {
    public record Entry(int row,String name,String role,String username,String error) {}
    public record Preview(int total,int valid,int rejected,List<Entry> entries) {}
    public record Imported(int created,int skipped,List<String> usernames) {}
    private final PortalUserRepository repository;
    private final PortalUserService users;
    private final GestaoTenantConfigService modules;
    private final String temporaryPassword;

    public EmployeeExcelImportService(PortalUserRepository repository,PortalUserService users,
            GestaoTenantConfigService modules,
            @Value("${app.tenant.module-temporary-password:gestao1234}") String temporaryPassword){
        this.repository=repository;this.users=users;this.modules=modules;this.temporaryPassword=temporaryPassword;
    }

    private static String fold(String raw) {
        String value=Normalizer.normalize(String.valueOf(raw==null?"":raw),Normalizer.Form.NFD)
                .replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT);
        return value.replaceAll("[^a-z0-9]+"," ").trim();
    }
    private static PortalRole roleOf(String value) {
        String t=fold(value);
        return switch(t){
            case "consultor","consultora","vendedor","vendedora","consultant" -> PortalRole.CONSULTANT;
            case "analista","analyst","analista de vistoria" -> PortalRole.ANALYST;
            case "supervisor","supervisora","supervisao","supervisao de analise" -> PortalRole.SUPERVISION_ANALYSIS;
            case "guincho","reboque","motorista de guincho" -> PortalRole.TOW_DRIVER;
            case "oficina","gerente de oficina","mecanico" -> PortalRole.WORKSHOP_MANAGER;
            case "eventos","operador de eventos","operadora de eventos" -> PortalRole.EVENT_OPERATOR;
            case "financeiro","compras","comprador","compradora" -> PortalRole.BUYER;
            default -> null;
        };
    }
    private boolean licensed(PortalRole role){
        return modules.enabled(switch(role){
            case CONSULTANT -> "COTA";
            case ANALYST,SUPERVISION_ANALYSIS,TOW_DRIVER,WORKSHOP_MANAGER,EVENT_OPERATOR -> "VISTORIA_EVENTOS";
            case BUYER -> "GESTAO_FINANCEIRA_COMERCIAL";
            default -> "NENHUM_MODULO";
        });
    }
    public Preview preview(MultipartFile file){
        if(file==null||file.isEmpty()||file.getSize()>5*1024*1024)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Envie XLSX com até 5 MB.");
        if(!String.valueOf(file.getOriginalFilename()).toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Envie Excel .xlsx (sem macros).");
        try (Workbook workbook=WorkbookFactory.create(new ByteArrayInputStream(file.getBytes()))) {
            Sheet sheet=workbook.getSheetAt(0);
            if(sheet.getLastRowNum()>500)throw new IllegalArgumentException("A planilha deve ter até 500 colaboradores.");
            Row header=sheet.getRow(0);
            if(header==null)throw new IllegalArgumentException("Cabeçalhos não encontrados.");
            DataFormatter fmt=new DataFormatter(Locale.forLanguageTag("pt-BR"));
            int nameCol=-1, roleCol=-1, userCol=-1;
            for(int i=0;i<Math.min(header.getLastCellNum(),30);i++){
                String cell=fold(fmt.formatCellValue(header.getCell(i)));
                if(cell.equals("nome")||cell.equals("nome completo")||cell.equals("funcionario"))nameCol=i;
                if(cell.equals("cargo")||cell.equals("funcao")||cell.equals("perfil"))roleCol=i;
                if(cell.equals("usuario")||cell.equals("login"))userCol=i;
            }
            if(nameCol<0||roleCol<0)throw new IllegalArgumentException("Use as colunas Nome e Cargo; Usuário é opcional.");
            List<Entry> entries=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for(int n=1;n<=sheet.getLastRowNum();n++){
                Row row=sheet.getRow(n);if(row==null)continue;
                String name=fmt.formatCellValue(row.getCell(nameCol)).trim();
                String cargo=fmt.formatCellValue(row.getCell(roleCol)).trim();
                String login=userCol<0?"":fmt.formatCellValue(row.getCell(userCol)).trim().toLowerCase(Locale.ROOT);
                if(name.isBlank()&&cargo.isBlank()&&login.isBlank())continue;
                PortalRole role=roleOf(cargo);
                if(login.isBlank()){
                    String[] parts=fold(name).split(" ");
                    login=parts.length>1 ? parts[0]+"."+parts[parts.length-1]:parts[0];
                }
                String error=null;
                if(name.length()<3||name.length()>140)error="Nome deve ter 3 a 140 caracteres.";
                else if(role==null)error="Cargo não reconhecido. Administrador e Ponto devem ser provisionados separadamente.";
                else if(!licensed(role))error="Módulo não contratado para este cargo.";
                else if(login.length()<3||login.length()>160||!login.matches("[a-z0-9._-]+"))
                    error="Login inválido.";
                else if(!seen.add(login))error="Login duplicado na planilha.";
                else if(repository.findByNormalizedUsername(login).isPresent())
                    error="Já existe usuário com este login; será ignorado.";
                entries.add(new Entry(n+1,name,role==null?cargo:role.name(),login,error));
            }
            int valid=(int)entries.stream().filter(x->x.error()==null).count();
            return new Preview(entries.size(),valid,entries.size()-valid,entries);
        }catch(java.io.IOException e){throw new IllegalArgumentException("Não foi possível ler o arquivo XLSX.",e);}
    }

    @Transactional
    public Imported confirm(MultipartFile file,String operator){
        Preview preview=preview(file);
        if(preview.total()==0)throw new IllegalArgumentException("Planilha sem colaboradores.");
        // Qualquer linha inválida bloqueia a importação: nenhuma conta fica parcial.
        if(preview.rejected()>0)throw new IllegalArgumentException(
            "Há "+preview.rejected()+" linhas rejeitadas. Corrija a planilha antes de confirmar.");
        List<String> created=new ArrayList<>();
        for(Entry entry:preview.entries()){
            PortalRole role=PortalRole.valueOf(entry.role());
            users.createImported(entry.username(),entry.name(),role,temporaryPassword,operator);
            created.add(entry.username());
        }
        return new Imported(created.size(),0,created);
    }
}
