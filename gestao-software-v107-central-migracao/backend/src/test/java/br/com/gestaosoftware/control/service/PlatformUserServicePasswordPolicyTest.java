package br.com.gestaosoftware.control.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformUserServicePasswordPolicyTest {
    @Test void rejectsSevenCharacters() {
        assertFalse(PlatformUserService.meetsMinimumPasswordLength("abcdef7"));
    }

    @Test void acceptsEightCharacters() {
        assertTrue(PlatformUserService.meetsMinimumPasswordLength("abcdefgh"));
    }

    @Test void acceptsLongPasswordsWithoutArtificialMaximum() {
        assertTrue(PlatformUserService.meetsMinimumPasswordLength("a".repeat(10000)));
    }

    @Test void rejectsNullAndEmpty() {
        assertFalse(PlatformUserService.meetsMinimumPasswordLength(null));
        assertFalse(PlatformUserService.meetsMinimumPasswordLength(""));
    }
}
