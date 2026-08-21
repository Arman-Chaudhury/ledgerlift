package io.ledgerlift.web;

import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportBatchRepository;
import io.ledgerlift.imports.ImportError;
import io.ledgerlift.imports.ImportService;
import io.ledgerlift.template.ParsePolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/imports")
@Tag(name = "Imports", description = "FBDI-style file uploads into the GL interface table")
public class ImportController {

    private final ImportService imports;
    private final ImportBatchRepository repo;

    public ImportController(ImportService imports, ImportBatchRepository repo) {
        this.imports = imports;
        this.repo = repo;
    }

    @Operation(summary = "Upload a GL interface template (CSV or ZIP). Idempotent on file checksum.")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportBatch> upload(@RequestParam("file") MultipartFile file,
                                              @RequestParam(value = "policy", defaultValue = "STRICT") ParsePolicy policy)
            throws IOException {
        var outcome = imports.upload(file.getBytes(), file.getOriginalFilename(), policy);
        if (outcome.duplicate()) {
            return ResponseEntity.ok(outcome.batch());
        }
        return ResponseEntity.created(URI.create("/api/v1/imports/" + outcome.batch().id())).body(outcome.batch());
    }

    @GetMapping
    public List<ImportBatch> list() {
        return repo.findAll();
    }

    @GetMapping("/{id}")
    public ImportBatch get(@PathVariable long id) {
        return imports.get(id);
    }

    @GetMapping("/{id}/errors")
    public List<ImportError> errors(@PathVariable long id) {
        imports.get(id);
        return repo.errors(id);
    }
}
