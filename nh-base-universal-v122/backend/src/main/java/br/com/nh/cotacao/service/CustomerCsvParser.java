package br.com.nh.cotacao.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;

/** Parser estrito de CSV UTF-8 para importação de associados. Nenhuma coluna é descartada silenciosamente. */
public final class CustomerCsvParser {
    private CustomerCsvParser() {}
    public static final int MAX_BYTES = 2_000_000;
    public static final int MAX_ROWS = 5_000;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Set<String> ALLOWED = Set.of("nome", "documento", "email", "telefone", "nascimento", "telefone2", "cep", "endereco", "numero", "complemento", "bairro", "cidade", "uf", "codigoexterno");
    public record CustomerRow(int sourceLine, String name, String document, String email, String phone,
                              String birthDate, String phone2, String zip, String address, String number,
                              String complement, String neighborhood, String city, String state, String externalId) {}
    public record Issue(int line, String code, String message) {}
    public record Parsed(List<CustomerRow> rows, List<Issue> errors, String sha256) {}

    public static Parsed parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("O arquivo deve ter entre 1 byte e 2 MB.");
        String raw = decode(bytes);
        if (raw.indexOf('\u0000') >= 0) throw new IllegalArgumentException("Arquivo inválido: contém bytes nulos.");
        if (raw.startsWith("\uFEFF")) raw = raw.substring(1);
        char delimiter = delimiter(raw);
        List<List<String>> table = split(raw, delimiter);
        if (table.size() < 2) throw new IllegalArgumentException("O CSV deve conter cabeçalho e pelo menos uma linha de dados.");
        if (table.size() - 1 > MAX_ROWS) throw new IllegalArgumentException("Limite de 5.000 registros por arquivo. Divida o arquivo em partes.");
        Map<String,Integer> columns = new LinkedHashMap<>();
        for (int i=0; i<table.get(0).size(); i++) {
            String label = normalizeHeader(table.get(0).get(i));
            if (label.isBlank() || !ALLOWED.contains(label)) throw new IllegalArgumentException("Coluna não reconhecida: '"+safeLabel(table.get(0).get(i))+"'. Corrija o modelo para não perder dados.");
            if (columns.putIfAbsent(label, i) != null) throw new IllegalArgumentException("Cabeçalho duplicado: "+label);
        }
        if (!columns.containsKey("nome") || !columns.containsKey("documento"))
            throw new IllegalArgumentException("O CSV precisa conter as colunas nome e documento (CPF ou CNPJ).");
        List<CustomerRow> rows = new ArrayList<>(); List<Issue> errors = new ArrayList<>(); Set<String> seen = new HashSet<>();
        for (int index=1; index<table.size(); index++) {
            List<String> r = table.get(index); int line=index+1;
            if (r.size()==1 && r.get(0).isBlank()) continue;
            if (r.size() != table.get(0).size()) { errors.add(new Issue(line,"COLUNAS","Quantidade de colunas diferente do cabeçalho.")); continue; }
            String name=cell(r,columns,"nome"),doc=digits(cell(r,columns,"documento")),email=cell(r,columns,"email").toLowerCase(Locale.ROOT),phone=digits(cell(r,columns,"telefone"));
            if (name.isBlank() || name.length()>160 || name.chars().anyMatch(c->c<32)) { errors.add(new Issue(line,"NOME","Nome obrigatório, com até 160 caracteres."));continue; }
            if (!validDocument(doc)) { errors.add(new Issue(line,"DOCUMENTO","CPF/CNPJ inválido. Confira os dígitos verificadores."));continue; }
            if (!seen.add(doc)) { errors.add(new Issue(line,"DUPLICADO","CPF/CNPJ duplicado dentro do arquivo."));continue; }
            if (!email.isBlank() && (email.length()>180 || !EMAIL.matcher(email).matches())) { errors.add(new Issue(line,"EMAIL","E-mail inválido."));continue; }
            if (phone.length()>30) {errors.add(new Issue(line,"TELEFONE","Telefone excede o limite."));continue;}
            String birth=cell(r,columns,"nascimento");
            if (!birth.isBlank()) { try { LocalDate d=LocalDate.parse(birth);if(d.isAfter(LocalDate.now()) || d.isBefore(LocalDate.of(1880,1,1)))throw new IllegalArgumentException(); }
                catch(Exception x){errors.add(new Issue(line,"NASCIMENTO","Use a data no formato AAAA-MM-DD."));continue;} }
            String state=cell(r,columns,"uf").toUpperCase(Locale.ROOT);
            if (!state.isBlank() && !state.matches("[A-Z]{2}")) { errors.add(new Issue(line,"UF","A UF deve conter duas letras."));continue; }
            String zip=digits(cell(r,columns,"cep"));
            if (!zip.isBlank() && zip.length()!=8){errors.add(new Issue(line,"CEP","CEP deve conter 8 dígitos."));continue;}
            if (!within(r,columns,"telefone2",30) || !within(r,columns,"endereco",220) || !within(r,columns,"numero",30) || !within(r,columns,"complemento",120) || !within(r,columns,"bairro",120) || !within(r,columns,"cidade",120) || !within(r,columns,"codigoexterno",160)) {
                errors.add(new Issue(line,"TAMANHO","Um campo ultrapassa o tamanho permitido."));continue;
            }
            rows.add(new CustomerRow(line,name,doc,email,phone,birth,digits(cell(r,columns,"telefone2")),zip,cell(r,columns,"endereco"),cell(r,columns,"numero"),cell(r,columns,"complemento"),cell(r,columns,"bairro"),cell(r,columns,"cidade"),state,cell(r,columns,"codigoexterno")));
        }
        return new Parsed(List.copyOf(rows),List.copyOf(errors),hexSha(bytes));
    }
    private static boolean within(List<String> row,Map<String,Integer> columns,String name,int length){return cell(row,columns,name).length()<=length;}
    private static String cell(List<String> row,Map<String,Integer> cols,String key){Integer i=cols.get(key);return i==null?"":row.get(i).trim();}
    private static String digits(String s){return s.replaceAll("\\D","");}
    private static String safeLabel(String s){return s.length()>70?s.substring(0,70):s;}
    private static String normalizeHeader(String s){
        String n=Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT),Normalizer.Form.NFD).replaceAll("\\p{M}","").replaceAll("[^a-z0-9]","");
        return switch(n){case "nomecompleto","razaosocial","cliente","associado"->"nome";
            case "cpf","cnpj","cpfcnpj","cpfoucnpj"->"documento";
            case "celular","whatsapp","fone","telefoneprincipal"->"telefone";
            case "datanascimento","datadenascimento"->"nascimento";
            case "celular2","telefonealternativo","telefone secundario","telefonesecundario"->"telefone2";
            case "logradouro","rua"->"endereco";case "bairro"->"bairro";case "estado"->"uf";
            case "idexterno","idlegado","codigocliente","codigo"->"codigoexterno";
            default->n;};
    }
    private static char delimiter(String raw){int end=raw.indexOf('\n');String head=end<0?raw:raw.substring(0,end);return head.chars().filter(c->c==';').count()>=head.chars().filter(c->c==',').count()?';':',';}
    private static String decode(byte[] bytes){
        try {return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(Exception e){throw new IllegalArgumentException("Arquivo deve estar em UTF-8. No Excel, use CSV UTF-8.");}
    }
    private static List<List<String>> split(String raw,char delim){
        List<List<String>> records=new ArrayList<>();List<String> record=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false;boolean closed=false;
        for(int i=0;i<raw.length();i++){
            char c=raw.charAt(i);
            if(c=='"'){
                if(quoted && i+1<raw.length() && raw.charAt(i+1)=='"'){value.append('"');i++;}
                else if(quoted){quoted=false;closed=true;}
                else if(value.isEmpty()&&!closed){quoted=true;}
                else throw new IllegalArgumentException("CSV inválido: aspas fora de um campo delimitado.");
            } else if(!quoted && (c==delim||c=='\n'||c=='\r')){
                record.add(value.toString());value.setLength(0);closed=false;
                if(c!=delim){if(!record.equals(List.of("")))records.add(record);record=new ArrayList<>();if(c=='\r'&&i+1<raw.length()&&raw.charAt(i+1)=='\n')i++;}
            } else {if(closed)throw new IllegalArgumentException("CSV inválido: conteúdo após aspas de fechamento.");value.append(c);if(value.length()>10000)throw new IllegalArgumentException("Campo CSV excessivamente longo.");}
        }
        if(quoted)throw new IllegalArgumentException("CSV inválido: aspas não fechadas.");
        if(!record.isEmpty()||!value.isEmpty()){record.add(value.toString());records.add(record);}
        return records;
    }
    private static String hexSha(byte[] bytes){try{byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);return HexFormat.of().formatHex(hash);}catch(Exception e){throw new IllegalStateException(e);}}
    public static boolean validDocument(String s){
        if(s==null || !(s.length()==11 || s.length()==14) || s.chars().distinct().count()==1)return false;
        if(s.length()==11){int d1=0,d2=0;for(int i=0;i<9;i++){int v=s.charAt(i)-'0';d1+=v*(10-i);d2+=v*(11-i);}int a=mod11(d1);return a==s.charAt(9)-'0'&&mod11(d2+a*2)==s.charAt(10)-'0';}
        int[] w1={5,4,3,2,9,8,7,6,5,4,3,2},w2={6,5,4,3,2,9,8,7,6,5,4,3,2};int a=0,b=0;for(int i=0;i<12;i++){int v=s.charAt(i)-'0';a+=v*w1[i];b+=v*w2[i];}
        int check1=mod11(a);return check1==s.charAt(12)-'0'&&mod11(b+check1*w2[12])==s.charAt(13)-'0';
    }
    private static int mod11(int sum){int value=11-(sum%11);return value>=10?0:value;}
}
