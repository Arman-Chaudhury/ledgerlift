package io.ledgerlift.web;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ImportControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from journal_lines");
        jdbc.update("delete from journal_headers");
        jdbc.update("delete from import_errors");
        jdbc.update("delete from gl_interface");
        jdbc.update("delete from import_batches");
    }

    static MockMultipartFile file(String name) throws Exception {
        return new MockMultipartFile("file", name, "text/csv", Files.readAllBytes(Path.of("src/test/resources/fixtures", name)));
    }

    @Test
    void uploadCreatesBatchThenDuplicateReturns200() throws Exception {
        mvc.perform(multipart("/api/v1/imports").file(file("clean_journal.csv")).header("X-API-Key", "dev-key"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("LOADED"))
                .andExpect(jsonPath("$.rowCount").value(6));
        mvc.perform(multipart("/api/v1/imports").file(file("clean_journal.csv")).header("X-API-Key", "dev-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rowCount").value(6));
        mvc.perform(get("/api/v1/imports").header("X-API-Key", "dev-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void unknownBatchIs404() throws Exception {
        mvc.perform(get("/api/v1/imports/999").header("X-API-Key", "dev-key"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("import batch 999 not found"));
    }

    @Test
    void errorsEndpointListsParseErrors() throws Exception {
        mvc.perform(multipart("/api/v1/imports").file(file("parse_errors.csv")).param("policy", "LENIENT").header("X-API-Key", "dev-key"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.parseErrors").value(5));
        mvc.perform(get("/api/v1/imports/{id}/errors", jdbc.queryForObject("select max(id) from import_batches", Long.class))
                        .header("X-API-Key", "dev-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(5)))
                .andExpect(jsonPath("$[0].ruleCode").value("PARSE"));
    }
}
