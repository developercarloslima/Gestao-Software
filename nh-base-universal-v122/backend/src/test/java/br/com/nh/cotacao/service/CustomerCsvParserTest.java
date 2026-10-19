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
}
