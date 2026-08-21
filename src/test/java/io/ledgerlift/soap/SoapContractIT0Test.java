package io.ledgerlift.soap;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/** Full-stack (real servlet container) contract tests for the SOAP, WSDL and OpenAPI surfaces. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SoapContractIT0Test {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // HttpURLConnection cannot replay a streamed POST body after a 401; use java.net.http instead.
        http.getRestTemplate().setRequestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory());
        jdbc.update("delete from journal_lines");
        jdbc.update("delete from journal_headers");
        jdbc.update("delete from import_errors");
        jdbc.update("delete from gl_interface");
        jdbc.update("delete from import_batches");
    }

    static String envelope(String body) {
        return "<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\" xmlns:ll=\"http://ledgerlift.io/imports\">"
                + "<soapenv:Header/><soapenv:Body>" + body + "</soapenv:Body></soapenv:Envelope>";
    }

    ResponseEntity<String> soap(String body, boolean withKey) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.TEXT_XML);
        if (withKey) h.set("X-API-Key", "dev-key");
        return http.postForEntity("/ws", new HttpEntity<>(envelope(body), h), String.class);
    }

    @Test
    void wsdlIsPublishedAndDescribesBothOperations() {
        ResponseEntity<String> r = http.getForEntity("/ws/imports.wsdl", String.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getBody()).contains("wsdl:definitions").contains("ImportsPort")
                .contains("getImportStatus").contains("submitImport").contains("http://ledgerlift.io/imports");
    }

    @Test
    void submitThenPollStatusOverSoap() throws Exception {
        String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of("src/test/resources/fixtures/clean_journal.csv")));
        ResponseEntity<String> submit = soap("<ll:submitImportRequest><ll:fileName>clean.csv</ll:fileName><ll:policy>STRICT</ll:policy>"
                + "<ll:content>" + b64 + "</ll:content><ll:validate>true</ll:validate></ll:submitImportRequest>", true);
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submit.getBody()).contains("submitImportResponse").contains("<ll:status>VALIDATED</ll:status>".replace("ll:", ""))
                .contains("rowCount>6<").contains("duplicate>false<");
        String id = submit.getBody().replaceAll("(?s).*batchId>(\\d+)<.*", "$1");

        ResponseEntity<String> status = soap("<ll:getImportStatusRequest><ll:batchId>" + id + "</ll:batchId></ll:getImportStatusRequest>", true);
        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(status.getBody()).contains("getImportStatusResponse").contains("status>VALIDATED<");

        // same bytes again -> duplicate=true, same batch id
        ResponseEntity<String> again = soap("<ll:submitImportRequest><ll:fileName>x.csv</ll:fileName>"
                + "<ll:content>" + b64 + "</ll:content></ll:submitImportRequest>", true);
        assertThat(again.getBody()).contains("duplicate>true<").contains("batchId>" + id + "<");
    }

    @Test
    void unknownBatchIsAClientSoapFault() {
        ResponseEntity<String> r = soap("<ll:getImportStatusRequest><ll:batchId>4242</ll:batchId></ll:getImportStatusRequest>", true);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR); // SOAP 1.1 faults ride on 500
        assertThat(r.getBody()).contains("Fault").contains("Client").contains("import batch 4242 not found");
    }

    @Test
    void soapOperationsNeedTheApiKeyButTheWsdlDoesNot() {
        ResponseEntity<String> r = soap("<ll:getImportStatusRequest><ll:batchId>1</ll:batchId></ll:getImportStatusRequest>", false);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/ws/imports.wsdl", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void restNeedsTheApiKeyButDocsAndHealthDoNot() {
        assertThat(http.getForEntity("/api/v1/imports", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders h = new HttpHeaders();
        h.set("X-API-Key", "wrong");
        assertThat(http.exchange("/api/v1/imports", org.springframework.http.HttpMethod.GET, new HttpEntity<>(h), String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> docs = http.getForEntity("/v3/api-docs", String.class);
        assertThat(docs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(docs.getBody()).contains("/api/v1/imports/{id}/validate").contains("/api/v1/imports/{id}/post")
                .contains("/api/v1/imports/{id}/errors.csv").contains("/api/v1/reports/{name}");
        assertThat(new String(docs.getBody().getBytes(StandardCharsets.UTF_8))).contains("\"openapi\"");
    }
}
