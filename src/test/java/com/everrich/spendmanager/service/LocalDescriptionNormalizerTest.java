package com.everrich.spendmanager.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for LocalDescriptionNormalizer.
 * 
 * These tests verify that the local normalizer produces similar results to the
 * LLM-based normalizer for common bank transaction description patterns.
 */
class LocalDescriptionNormalizerTest {

    private LocalDescriptionNormalizer normalizer;

    @BeforeEach
    void setUp() {
        normalizer = new LocalDescriptionNormalizer();
    }

    @Nested
    @DisplayName("Null and Empty Input Handling")
    class NullAndEmptyInputTests {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "\n"})
        @DisplayName("Should return empty string for null, empty, or whitespace-only input")
        void shouldReturnEmptyForNullOrEmpty(String input) {
            assertEquals("", normalizer.normalize(input));
        }
    }

    @Nested
    @DisplayName("IBAN Removal")
    class IbanRemovalTests {

        @Test
        @DisplayName("Should remove German IBAN")
        void shouldRemoveGermanIban() {
            String input = "Überweisung Sandeep Joseph DE212004115508674269";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("DE212004115508674269"));
            assertTrue(result.contains("Sandeep Joseph"));
        }

        @Test
        @DisplayName("Should remove French IBAN")
        void shouldRemoveFrenchIban() {
            String input = "Transfer to FR7630006000011234567890189";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("FR7630006000011234567890189"));
        }

        @Test
        @DisplayName("Should remove multiple IBANs")
        void shouldRemoveMultipleIbans() {
            String input = "Transfer from DE89370400440532013000 to FR7630006000011234567890189";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("DE89370400440532013000"));
            assertFalse(result.contains("FR7630006000011234567890189"));
        }
    }

    @Nested
    @DisplayName("BIC/SWIFT Code Removal")
    class BicRemovalTests {

        @Test
        @DisplayName("Should remove 8-character BIC")
        void shouldRemove8CharBic() {
            String input = "Transfer DEUTDEDB Berlin";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("DEUTDEDB"));
            assertTrue(result.contains("Berlin"));
        }

        @Test
        @DisplayName("Should remove 11-character BIC")
        void shouldRemove11CharBic() {
            String input = "Überweisung (Echtzeit) Sandeep Joseph COBADEHD055";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("COBADEHD055"));
            assertTrue(result.contains("Sandeep Joseph"));
        }

        @Test
        @DisplayName("Should remove BIC with XXX branch code")
        void shouldRemoveBicWithXxxBranch() {
            String input = "Payment via INGDDEFFXXX";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("INGDDEFFXXX"));
        }
    }

    @Nested
    @DisplayName("Account Number Removal")
    class AccountNumberRemovalTests {

        @Test
        @DisplayName("Should remove Kto. format account number")
        void shouldRemoveKtoFormat() {
            String input = "UNICREDIT BANK GMBH Kto.0046348710";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("Kto.0046348710"));
            assertFalse(result.contains("0046348710"));
        }

        @Test
        @DisplayName("Should remove Konto: format account number")
        void shouldRemoveKontoFormat() {
            String input = "Payment from Konto: 123456789";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("Konto: 123456789"));
            assertFalse(result.contains("123456789"));
        }

        @Test
        @DisplayName("Should remove Kto without dot")
        void shouldRemoveKtoWithoutDot() {
            String input = "Transfer Kto 987654321";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("987654321"));
        }
    }

    @Nested
    @DisplayName("Date and Timestamp Removal")
    class DateRemovalTests {

        @Test
        @DisplayName("Should remove ISO timestamp")
        void shouldRemoveIsoTimestamp() {
            String input = "Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("2025-10-23T19:07:36"));
            assertTrue(result.contains("Deutsche Bank"));
        }

        @Test
        @DisplayName("Should remove German date with PER prefix")
        void shouldRemoveGermanDateWithPer() {
            String input = "UNICREDIT BANK GMBH PER 31.07.25";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("PER 31.07.25"));
            assertFalse(result.contains("31.07.25"));
        }

        @Test
        @DisplayName("Should remove standalone German date")
        void shouldRemoveStandaloneGermanDate() {
            String input = "Payment on 03.12.2025";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("03.12.2025"));
        }

        @Test
        @DisplayName("Should remove ISO date")
        void shouldRemoveIsoDate() {
            String input = "Transaction 2025-10-23 completed";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("2025-10-23"));
        }
    }

    @Nested
    @DisplayName("Reference Number Removal")
    class ReferenceRemovalTests {

        @Test
        @DisplayName("Should remove Mandatsref")
        void shouldRemoveMandatsref() {
            String input = "SEPA Payment Mandatsref: 588880043460001";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("Mandatsref"));
            assertFalse(result.contains("588880043460001"));
        }

        @Test
        @DisplayName("Should remove End-to-End-Ref")
        void shouldRemoveEndToEndRef() {
            String input = "Payment End-to-End-Ref.: 140500028161";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("End-to-End-Ref"));
            assertFalse(result.contains("140500028161"));
        }

        @Test
        @DisplayName("Should remove Gläubiger-ID")
        void shouldRemoveGlaeubigerId() {
            String input = "SEPA Lastschrift Gläubiger-ID: DE50ZZZ00000094129";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("Gläubiger-ID"));
            assertFalse(result.contains("DE50ZZZ00000094129"));
        }

        @Test
        @DisplayName("Should remove generic Ref.")
        void shouldRemoveGenericRef() {
            String input = "Payment Ref. 123456789";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("Ref."));
        }
    }

    @Nested
    @DisplayName("Long Number Removal")
    class LongNumberRemovalTests {

        @Test
        @DisplayName("Should remove standalone long numbers (10+ digits)")
        void shouldRemoveLongNumbers() {
            String input = "Transaction 1234567890123 completed";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("1234567890123"));
        }

        @Test
        @DisplayName("Should preserve short numbers")
        void shouldPreserveShortNumbers() {
            String input = "Amount 100 EUR";
            String result = normalizer.normalize(input);
            assertTrue(result.contains("100"));
        }
    }

    @Nested
    @DisplayName("Separator Cleaning")
    class SeparatorCleaningTests {

        @Test
        @DisplayName("Should replace multiple slashes with space")
        void shouldReplaceMultipleSlashes() {
            String input = "Deutsche Bank//Wiesloch";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("//"));
            assertTrue(result.contains("Deutsche Bank"));
            assertTrue(result.contains("Wiesloch"));
        }

        @Test
        @DisplayName("Should remove ellipsis")
        void shouldRemoveEllipsis() {
            String input = "UNICREDIT BANK GMBH...";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("..."));
        }

        @Test
        @DisplayName("Should remove country code suffix")
        void shouldRemoveCountryCodeSuffix() {
            String input = "Deutsche Bank/DE";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("/DE"));
        }

        @Test
        @DisplayName("Should normalize multiple spaces")
        void shouldNormalizeMultipleSpaces() {
            String input = "Deutsche   Bank    Wiesloch";
            String result = normalizer.normalize(input);
            assertFalse(result.contains("  "));
        }
    }

    @Nested
    @DisplayName("Case Normalization")
    class CaseNormalizationTests {

        @Test
        @DisplayName("Should convert long uppercase words to title case")
        void shouldConvertLongUppercaseToTitleCase() {
            String input = "UNICREDIT BANK GMBH";
            String result = normalizer.normalize(input);
            assertTrue(result.contains("Unicredit"));
            assertTrue(result.contains("Bank"));
            assertTrue(result.contains("Gmbh"));
        }

        @Test
        @DisplayName("Should preserve short uppercase words (likely acronyms)")
        void shouldPreserveShortUppercase() {
            String input = "AMAZON DE";
            String result = normalizer.normalize(input);
            // "DE" is only 2 chars, should stay uppercase
            assertTrue(result.contains("DE"));
            // "AMAZON" is > 3 chars, should be title cased
            assertTrue(result.contains("Amazon"));
        }

        @Test
        @DisplayName("Should handle mixed case input")
        void shouldHandleMixedCase() {
            String input = "Deutsche Bank GMBH";
            String result = normalizer.normalize(input);
            assertTrue(result.contains("Deutsche"));
            assertTrue(result.contains("Bank"));
            assertTrue(result.contains("Gmbh"));
        }

        @Test
        @DisplayName("Should handle hyphenated words")
        void shouldHandleHyphenatedWords() {
            String input = "BARGELDEIN-/AUSZAHLUNG";
            String result = normalizer.normalize(input);
            // Should title-case each part after hyphen
            assertTrue(result.toLowerCase().contains("bargeldein"));
        }
    }

    @Nested
    @DisplayName("SEPA Suffix Removal")
    class SepaSuffixRemovalTests {

        @Test
        @DisplayName("Should remove 'wiederholend' suffix")
        void shouldRemoveWiederholend() {
            String input = "SEPA-BASISLASTSCHRIFT wiederholend";
            String result = normalizer.normalize(input);
            assertFalse(result.toLowerCase().contains("wiederholend"));
        }

        @Test
        @DisplayName("Should remove 'einmalig' suffix")
        void shouldRemoveEinmalig() {
            String input = "SEPA-ÜBERWEISUNG einmalig";
            String result = normalizer.normalize(input);
            assertFalse(result.toLowerCase().contains("einmalig"));
        }
    }

    @Nested
    @DisplayName("Real-World Examples from LLM Prompt")
    class RealWorldExamplesTests {

        @Test
        @DisplayName("Example 1: UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25...")
        void shouldNormalizeUnicreditExample() {
            String input = "UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25...";
            String result = normalizer.normalize(input);
            
            // Should contain bank name (title-cased)
            assertTrue(result.toLowerCase().contains("unicredit"));
            assertTrue(result.toLowerCase().contains("bank"));
            assertTrue(result.toLowerCase().contains("gmbh"));
            
            // Should NOT contain account number, date, or ellipsis
            assertFalse(result.contains("0046348710"));
            assertFalse(result.contains("31.07.25"));
            assertFalse(result.contains("..."));
        }

        @Test
        @DisplayName("Example 2: Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36")
        void shouldNormalizeDeutscheBankExample() {
            String input = "Bargeldein-/auszahlung Deutsche Bank//Wiesloch/DE 2025-10-23T19:07:36";
            String result = normalizer.normalize(input);
            
            // Should contain transaction type, bank name, and location
            assertTrue(result.toLowerCase().contains("bargeldein"));
            assertTrue(result.toLowerCase().contains("deutsche bank"));
            assertTrue(result.toLowerCase().contains("wiesloch"));
            
            // Should NOT contain country code or timestamp
            assertFalse(result.contains("/DE"));
            assertFalse(result.contains("2025-10-23T19:07:36"));
            assertFalse(result.contains("//"));
        }

        @Test
        @DisplayName("Example 3: Überweisung (Echtzeit) Sandeep Joseph COBADEHD055 DE212004115508674269...")
        void shouldNormalizeUeberweisungExample() {
            String input = "Überweisung (Echtzeit) Sandeep Joseph COBADEHD055 DE212004115508674269...";
            String result = normalizer.normalize(input);
            
            // Should contain transaction type and person name
            assertTrue(result.contains("Überweisung"));
            assertTrue(result.contains("Echtzeit"));
            assertTrue(result.toLowerCase().contains("sandeep joseph"));
            
            // Should NOT contain BIC or IBAN
            assertFalse(result.contains("COBADEHD055"));
            assertFalse(result.contains("DE212004115508674269"));
            assertFalse(result.contains("..."));
        }
    }

    @Nested
    @DisplayName("Complex Real-World Descriptions")
    class ComplexDescriptionTests {

        @Test
        @DisplayName("Should handle full SEPA description with all reference fields")
        void shouldHandleFullSepaDescription() {
            String input = "Gemeinde Dielheim / 588880043460/Abwasser Wasser/Abwasser Rechnung/Wasser Wasser/Abwasser Rechnung / End-to-End-Ref.: 140500028161 / Mandatsref: 588880043460001 / Gläubiger-ID: DE50ZZZ00000094129 / SEPA-BASISLASTSCHRIFT wiederholend";
            String result = normalizer.normalize(input);
            
            // Should preserve meaningful content
            assertTrue(result.toLowerCase().contains("gemeinde dielheim"));
            assertTrue(result.toLowerCase().contains("abwasser"));
            assertTrue(result.toLowerCase().contains("wasser"));
            
            // Should remove all reference numbers and SEPA suffix
            assertFalse(result.contains("End-to-End-Ref"));
            assertFalse(result.contains("Mandatsref"));
            assertFalse(result.contains("Gläubiger-ID"));
            assertFalse(result.toLowerCase().contains("wiederholend"));
        }

        @Test
        @DisplayName("Should handle Amazon purchase description")
        void shouldHandleAmazonDescription() {
            String input = "AMAZON EU S.A R.L., LUX AMAZON.DE 123456789012 LU";
            String result = normalizer.normalize(input);
            
            // Should preserve Amazon reference
            assertTrue(result.toLowerCase().contains("amazon"));
        }

        @Test
        @DisplayName("Should handle supermarket description")
        void shouldHandleSupermarketDescription() {
            String input = "REWE SAGT DANKE 12345678//HEIDELBERG/DE 2025-06-15T14:23:45";
            String result = normalizer.normalize(input);
            
            // Should preserve store name and location
            assertTrue(result.toLowerCase().contains("rewe"));
            assertTrue(result.toLowerCase().contains("danke"));
            assertTrue(result.toLowerCase().contains("heidelberg"));
            
            // Should remove timestamp and country code
            assertFalse(result.contains("2025-06-15T14:23:45"));
            assertFalse(result.contains("/DE"));
        }
    }

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Should complete normalization in under 1ms for typical input")
        void shouldBefast() {
            String input = "UNICREDIT BANK GMBH Kto.0046348710 PER 31.07.25... COBADEHD055 DE212004115508674269";
            
            // Warm up
            for (int i = 0; i < 100; i++) {
                normalizer.normalize(input);
            }
            
            // Measure
            long start = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                normalizer.normalize(input);
            }
            long duration = System.nanoTime() - start;
            double avgMicros = duration / 1000.0 / 1000.0;
            
            // Should average less than 1ms (1000 microseconds)
            assertTrue(avgMicros < 1000, "Average normalization time should be under 1ms, but was " + avgMicros + " microseconds");
        }
    }
}