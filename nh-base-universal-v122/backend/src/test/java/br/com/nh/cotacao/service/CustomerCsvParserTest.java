package br.com.nh.cotacao.service;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class CustomerCsvParserTest {
  private static byte[] csv(String content){return content.getBytes(StandardCharsets.UTF_8);}
  @Test void acceptsUtf8WithQuotedNames(){
    var data=CustomerCsvParser.parse(csv("nome;cpf;email;telefone\n\"Silva; Maria\";529.982.247-25;maria@example.invalid;82999999999\n"));
    assertEquals(1,data.rows().size());assertTrue(data.errors().isEmpty());assertEquals("Silva; Maria",data.rows().get(0).name());
  }
  @Test void rejectsUnknownColumnsRatherThanDiscard(){assertThrows(IllegalArgumentException.class,()->CustomerCsvParser.parse(csv("nome;documento;segredo\nA;52998224725;teste")));}
  @Test void duplicatesAreExplicitlyBlocked(){var data=CustomerCsvParser.parse(csv("nome;documento\nA;52998224725\nB;52998224725\n"));assertEquals(1,data.rows().size());assertEquals("DUPLICADO",data.errors().get(0).code());}
  @Test void invalidDocumentCannotBeImported(){var data=CustomerCsvParser.parse(csv("nome;documento\nA;11111111111\n"));assertEquals(1,data.errors().size());}
  @Test void cnpjCheckDigits(){assertTrue(CustomerCsvParser.validDocument("11222333000181"));assertFalse(CustomerCsvParser.validDocument("11222333000182"));}
  @Test void bomAccepted(){var data=CustomerCsvParser.parse(csv("\uFEFFnome,documento\nA,52998224725\n"));assertEquals(1,data.rows().size());}
  @Test void rejectMalformedUtf8(){assertThrows(IllegalArgumentException.class,()->CustomerCsvParser.parse(new byte[]{(byte)0xc3,0x28}));}
  @Test void rejectsFutureBirthDates(){var data=CustomerCsvParser.parse(csv("nome;documento;nascimento\nA;52998224725;2999-01-01\n"));assertEquals("NASCIMENTO",data.errors().get(0).code());}
  @Test void acceptsVehicleColumnsAndLabelsFromInspectionForm(){
    var csvText="Nome do associado;CPF;WhatsApp;Placa;Veículo 0 km;Tipo de veículo (FIPE);Marca do veículo (digite ou selecione);Modelo do veículo (digite ou selecione);Ano do modelo;Versão de veículo (Tabela FIPE);Código FIPE;Valor FIPE encontrado\n"
      +"Cícero Correia Costa;529.982.247-25;82999999999;SAG3J73;Não;moto;HONDA;NXR160 Bros ESDD;2023;NXR160 Bros ESDD;811011-4;18.500,90\n";
    var parsed=CustomerCsvParser.parse(csv(csvText));
    assertTrue(parsed.errors().isEmpty(),()->parsed.errors().toString());
    var row=parsed.rows().get(0);
    assertTrue(row.hasVehicle());assertEquals("SAG3J73",row.plate());
    assertEquals("moto",row.vehicleType());assertEquals("2023",row.modelYear());
    assertEquals("18500.90",CustomerCsvParser.normalizeMoney(row.fipeValue()));
  }
  @Test void rejectsMalformedVehiclePlate(){
    var parsed=CustomerCsvParser.parse(csv("nome;documento;placa;modelo\nA;52998224725;INVALIDA;Corolla\n"));
    assertEquals("PLACA",parsed.errors().get(0).code());
  }
  @Test void oldCustomerOnlyCsvRemainsValid(){
    var parsed=CustomerCsvParser.parse(csv("nome;documento\nA;52998224725\n"));
    assertFalse(parsed.rows().get(0).hasVehicle());
  }

}
