package com.overdrive.app.parking.signage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The signage grammar must be strict: real signs parse, plates and noise never do. */
public class SignageGrammarTest {

    private static SignageGrammar.Observation obs(String text, float conf) {
        return new SignageGrammar.Observation(text, conf, 0, 0.08f);
    }

    private static SignageGrammar.Result parse(String... texts) {
        List<SignageGrammar.Observation> list = new ArrayList<>();
        for (String t : texts) list.add(obs(t, 0.9f));
        return SignageGrammar.parse(list);
    }

    @Test
    public void basementCodesAndZones() throws Exception {
        SignageGrammar.Result r = parse("B2", "ZONE C", "BAY 142");
        assertEquals("B2", r.level);
        assertEquals("C", r.zone);
        assertEquals("142", r.bay);
        assertEquals("B2 · Zone C · Bay 142", r.label());
        assertTrue(r.confidence() > 0.7f);
        assertTrue(r.toJson().getBoolean("found"));
    }

    @Test
    public void malayAndWordForms() {
        assertEquals("Level 3", parse("ARAS 3").level);
        assertEquals("Level 2", parse("Tingkat 2").level);
        assertEquals("B1", parse("LEVEL B1").level);
        assertEquals("G", parse("Ground Floor").level);
        assertEquals("LG", parse("LOWER GROUND").level);
        assertEquals("B2", parse("BASEMENT 2").level);
        assertEquals("Level 2", parse("2F").level);
        assertEquals("B1", parse("-1F").level);
        assertEquals("B2", parse("B2F").level);
        assertEquals("Level 3", parse("3/F").level);
        assertEquals("P3", parse("P3").level);
        assertEquals("B", parse("ZON B").zone);
        assertEquals("Red", parse("RED ZONE").zone);
        assertEquals("Blue", parse("ZONE BLUE").zone);
        assertEquals("23", parse("PETAK 23").bay);
        assertEquals("118", parse("NO. 118").bay);
    }

    @Test
    public void ctcOutputWithoutSpacesStillParses() {
        // The bundled PP-OCRv3 recogniser emits no spaces: "ZONE C" → "ZONEC".
        SignageGrammar.Result r = parse("B2", "ZONEC", "BAY142");
        assertEquals("B2", r.level);
        assertEquals("C", r.zone);
        assertEquals("142", r.bay);
        assertEquals("Level 2", parse("LEVEL2").level);
        assertEquals("Level 3", parse("ARAS3").level);
        assertEquals("12", parse("LOT12").bay);
        assertEquals("Red", parse("RED ZONE").zone);      // "ZONE" alone never yields a zone
    }

    @Test
    public void latinLanguagesOfTheAppParse() {
        // Accents are folded, so the words are recognised as printed on the sign.
        assertEquals("B2", parse("SÓTANO 2").level);            // es
        assertEquals("B1", parse("PLANTA -1").level);           // es / ca
        assertEquals("Level 2", parse("NIVEL 2").level);        // es
        assertEquals("Level 3", parse("PISO 3").level);         // pt
        assertEquals("B2", parse("SUBSOLO 2").level);           // pt-BR
        assertEquals("Level 2", parse("ÉTAGE 2").level);        // fr
        assertEquals("B2", parse("SOUS-SOL 2").level);          // fr
        assertEquals("Level 4", parse("EBENE 4").level);        // de
        assertEquals("Level 2", parse("2. OG").level);          // de
        assertEquals("B1", parse("1. UG").level);               // de
        assertEquals("Level 3", parse("PARKDECK 3").level);     // de
        assertEquals("B2", parse("UNTERGESCHOSS 2").level);     // de
        assertEquals("B1", parse("INTERRATO").level);           // it
        assertEquals("Level 2", parse("PIANO 2").level);        // it
        assertEquals("Level 1", parse("VERDIEPING 1").level);   // nl
        assertEquals("Level 2", parse("ETASJE 2").level);       // nb
        assertEquals("Level 2", parse("PODLAŽÍ 2").level);      // cs
        assertEquals("Level 2", parse("KAT 2").level);          // tr
        assertEquals("B1", parse("BODRUM").level);              // tr
        assertEquals("Level 3", parse("TẦNG 3").level);         // vi
        assertEquals("B2", parse("HẦM B2").level);              // vi
        assertEquals("Level 3", parse("ЭТАЖ 3").level);         // ru (Cyrillic passes through)
        assertEquals("A", parse("ZONA A").zone);                // es / pt / it / cs
        assertEquals("C", parse("SETOR C").zone);               // pt-BR
        assertEquals("B", parse("BEREICH B").zone);             // de
        assertEquals("A", parse("KHU A").zone);                 // vi
        assertEquals("A", parse("BÖLGE A").zone);               // tr
        assertEquals("12", parse("VAGA 12").bay);               // pt-BR
        assertEquals("8", parse("PLAZA 8").bay);                // es
        assertEquals("23", parse("PLATZ 23").bay);              // de
        assertEquals("45", parse("МЕСТО 45").bay);              // ru
        assertEquals("7", parse("PLASS 7").bay);                // nb
        assertEquals("G", parse("ERDGESCHOSS").level);          // de ground floor
        assertTrue("HAM alone is a word, not a basement", parse("HAM").isEmpty());
    }

    @Test
    public void nonLatinScriptsParseWhenASideLoadedRecogniserEmitsThem() {
        assertEquals("B2", parse("地下2层").level);              // zh-CN
        assertEquals("Level 3", parse("3楼").level);            // zh-CN
        assertEquals("Level 3", parse("三楼").level);           // zh-CN numerals
        assertEquals("B2", parse("B2层").level);                // zh-CN code + unit
        assertEquals("Level 2", parse("２階").level);           // ja full-width digit
        assertEquals("B1", parse("地下1階").level);             // ja
        assertEquals("B3", parse("지하 3층").level);            // ko
        assertEquals("Level 2", parse("2층").level);            // ko
        assertEquals("Level 2", parse("ชั้น 2").level);          // th
        assertEquals("B1", parse("ชั้นใต้ดิน 1").level);          // th basement
        assertEquals("Level 2", parse("קומה 2").level);         // he
        assertEquals("B1", parse("קומה -1").level);            // he
        assertEquals("B2", parse("מינוס 2").level);            // he "minus 2"
        assertEquals("Level 2", parse("2 קומה").level);         // he, value-first (RTL output order)
        assertEquals("Level 3", parse("الطابق ٣").level);       // ar with Arabic-Indic digit
        assertEquals("Level 2", parse("मंजिल २").level);          // hi with Devanagari digit
        assertEquals("A", parse("A区").zone);                   // zh
        assertEquals("3", parse("منطقة 3").zone);               // ar
        assertEquals("12", parse("12号车位").bay);              // zh
        assertEquals("35", parse("35番").bay);                  // ja
        assertEquals("B2", parse("Ｂ２").level);                // full-width Latin code
    }

    @Test
    public void ocrLookAlikesInsideKeywordValuesAreRepaired() {
        assertEquals("Level 2", parse("LEVEL Z").level);      // Z ↔ 2
        assertEquals("Level 5", parse("FLOOR S").level);      // S ↔ 5
        assertEquals("B1", parse("BASEMENT I").level);        // I ↔ 1
    }

    @Test
    public void bareBayIsAcceptedButPlatesAreNot() {
        SignageGrammar.Result bare = parse("C142");
        assertEquals("C142", bare.bay);
        assertNull(bare.level);

        SignageGrammar.Result plate = parse("WXY 1234", "AB 1234", "VBD 7788", "1234");
        assertTrue("four-digit groups are plates, never bays", plate.isEmpty());
    }

    @Test
    public void zoneLetterIsStrippedFromBareBayWhenRedundant() {
        SignageGrammar.Result r = parse("ZONE C", "C142");
        assertEquals("C", r.zone);
        assertEquals("142", r.bay);
    }

    @Test
    public void noiseDoesNotParse() {
        assertTrue(parse("EXIT", "KELUAR", "NO SMOKING", "SPEED LIMIT 10", "PAY HERE", "LEVEL", "BASE").isEmpty());
        assertTrue(parse("PARKING", "RESERVED", "THANK YOU").isEmpty());
        // Words that END in F must not become "<look-alike digit>F" levels.
        assertTrue(parse("IF", "MOTIF", "OFF", "STAFF", "SELF PARK").isEmpty());
        // Ordinary words that happen to be level words elsewhere need a value.
        assertTrue(parse("KAT", "PISO", "PIANO", "AREA", "BLOCK", "SEC", "NO").isEmpty());
        assertTrue(SignageGrammar.parse(null).isEmpty());
        assertTrue(SignageGrammar.parse(new ArrayList<>()).isEmpty());
    }

    @Test
    public void votesAcrossFramesBeatOneLoudMisread() {
        List<SignageGrammar.Observation> list = Arrays.asList(
                new SignageGrammar.Observation("B2", 0.6f, 0, 0.1f),
                new SignageGrammar.Observation("B2", 0.6f, 1, 0.1f),
                new SignageGrammar.Observation("B3", 0.7f, 2, 0.1f));
        SignageGrammar.Result r = SignageGrammar.parse(list);
        assertEquals("B2", r.level);
        assertEquals(2, r.evidence.size());
    }

    @Test
    public void lowConfidenceAndTinyTextAreRejected() {
        List<SignageGrammar.Observation> list = Arrays.asList(
                new SignageGrammar.Observation("B2", 0.3f, 0, 0.01f));   // 0.3 × 0.6 × 0.85 < 0.35
        assertTrue(SignageGrammar.parse(list).isEmpty());
    }

    @Test
    public void codeWithTwoDigitsIsLevelNotBay() {
        SignageGrammar.Result r = parse("P10");
        assertEquals("P10", r.level);
        assertNull(r.bay);
        SignageGrammar.Result b142 = parse("B142");
        assertNull("three digits behind a code is a bay-like token, not a level", b142.level);
        assertEquals("B142", b142.bay);
    }

    @Test
    public void helpers() {
        assertEquals("120", SignageGrammar.digits("IZO"));
        assertEquals("Level 4", SignageGrammar.canonicalLevel("4"));
        assertEquals("G", SignageGrammar.canonicalLevel("0"));
        assertNull(SignageGrammar.canonicalLevel("99"));
        assertEquals("Roof", SignageGrammar.namedLevel("ROOF", null));
    }
}
