package io.ledgerlift.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

class TemplateParserTest {

    static byte[] fixture(String name) throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/fixtures", name));
    }

    @Test
    void parsesCleanFileWithHeaderRow() throws IOException {
        ParseResult r = new TemplateParser(ParsePolicy.STRICT).parse(fixture("clean_journal.csv"), "clean_journal.csv");
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.rows()).hasSize(6);
        GlInterfaceRow first = r.rows().get(0);
        assertThat(first.lineNo()).isEqualTo(1);
        assertThat(first.ledgerName()).isEqualTo("US Primary");
        assertThat(first.accountCode()).isEqualTo("6000");
        assertThat(first.enteredDr()).isEqualByComparingTo("125000.00");
        assertThat(first.enteredCr()).isNull();
        assertThat(first.accountingDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        // quoted field with an embedded comma
        assertThat(r.rows().get(4).lineDescription()).isEqualTo("Product sales, retail");
    }

    @Test
    void headerRowIsOptional() {
        String body = "US Primary,Mar-26,J,d,1000,USD,1.00,,2026-03-02,R\n";
        ParseResult r = new TemplateParser(ParsePolicy.STRICT).parse(body.getBytes(StandardCharsets.UTF_8), "x.csv");
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).lineNo()).isEqualTo(1);
    }

    @Test
    void toleratesBomBlankLinesAndComments() {
        String body = "﻿LEDGER_NAME,PERIOD_NAME,JOURNAL_NAME,LINE_DESCRIPTION,ACCOUNT_CODE,CURRENCY_CODE,ENTERED_DR,ENTERED_CR,ACCOUNTING_DATE,REFERENCE\r\n"
                + "\r\n# comment\r\n"
                + "US Primary,Mar-26,J,d,1000,usd,\"1,250.50\",,03/02/2026,R\r\n";
        ParseResult r = new TemplateParser(ParsePolicy.STRICT).parse(body.getBytes(StandardCharsets.UTF_8), "x.csv");
        assertThat(r.hasErrors()).isFalse();
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).enteredDr()).isEqualByComparingTo(new BigDecimal("1250.50"));
        assertThat(r.rows().get(0).currencyCode()).isEqualTo("USD");
        assertThat(r.rows().get(0).accountingDate()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void everyParseErrorIsKeptWithLineNumberAndColumn() throws IOException {
        ParseResult r = new TemplateParser(ParsePolicy.LENIENT).parse(fixture("parse_errors.csv"), "parse_errors.csv");
        assertThat(r.rows()).hasSize(6); // lenient keeps every row
        assertThat(r.errors()).extracting(ParseError::lineNo, ParseError::column).containsExactly(
                org.assertj.core.groups.Tuple.tuple(2, "ENTERED_DR"),
                org.assertj.core.groups.Tuple.tuple(3, "ACCOUNTING_DATE"),
                org.assertj.core.groups.Tuple.tuple(4, null),
                org.assertj.core.groups.Tuple.tuple(5, "ENTERED_DR"),
                org.assertj.core.groups.Tuple.tuple(6, "CURRENCY_CODE"));
        assertThat(r.rows().get(1).enteredDr()).isNull();      // failed field nulled
        assertThat(r.rows().get(1).accountCode()).isEqualTo("1000"); // good fields kept
        assertThat(r.rows().get(3).accountingDate()).isNull(); // padded short row
        assertThat(r.rejected()).isFalse();
    }

    @Test
    void strictPolicyRejectsFileWithAnyParseError() throws IOException {
        ParseResult r = new TemplateParser(ParsePolicy.STRICT).parse(fixture("parse_errors.csv"), "parse_errors.csv");
        assertThat(r.rejected()).isTrue();
        assertThat(r.errors()).hasSize(5);
    }

    @Test
    void negativeAmountsAreParseErrors() {
        String body = "US Primary,Mar-26,J,d,1000,USD,-5.00,,2026-03-02,R\n";
        ParseResult r = new TemplateParser(ParsePolicy.LENIENT).parse(body.getBytes(StandardCharsets.UTF_8), "x.csv");
        assertThat(r.errors()).singleElement().extracting(ParseError::message).asString().contains("negative");
    }

    @Test
    void readsFirstCsvInsideZip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("__MACOSX/._junk.csv"));
            z.write("garbage".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("GlInterface.csv"));
            z.write(fixture("clean_journal.csv"));
            z.closeEntry();
        }
        ParseResult r = new TemplateParser(ParsePolicy.STRICT).parse(bos.toByteArray(), "upload.zip");
        assertThat(r.rows()).hasSize(6);
    }

    @Test
    void zipWithoutCsvIsRejectedLoudly() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("readme.txt"));
            z.write("hi".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        assertThatThrownBy(() -> new TemplateParser(ParsePolicy.STRICT).parse(bos.toByteArray(), "upload.zip"))
                .isInstanceOf(TemplateException.class)
                .hasMessageContaining("no .csv entry");
    }
}
