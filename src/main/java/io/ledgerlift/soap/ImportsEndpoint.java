package io.ledgerlift.soap;

import io.ledgerlift.imports.ImportBatch;
import io.ledgerlift.imports.ImportService;
import io.ledgerlift.template.ParsePolicy;
import io.ledgerlift.validation.ValidationService;
import java.util.Base64;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * The same import operations as the REST API, for ERP integrations that speak
 * SOAP (Oracle's ERP Integration Service is the model: submit by base64, poll
 * status). DOM-based on purpose: no generated classes to keep in sync.
 */
@Endpoint
public class ImportsEndpoint {

    private static final String NS = WsConfig.NAMESPACE;

    private final ImportService imports;
    private final ValidationService validation;

    public ImportsEndpoint(ImportService imports, ValidationService validation) {
        this.imports = imports;
        this.validation = validation;
    }

    @PayloadRoot(namespace = NS, localPart = "getImportStatusRequest")
    @ResponsePayload
    public Element getImportStatus(@RequestPayload Element req) {
        long id = Long.parseLong(text(req, "batchId"));
        ImportBatch b = imports.get(id);
        Document doc = newDoc();
        Element res = doc.createElementNS(NS, "getImportStatusResponse");
        res.appendChild(batch(doc, b));
        return res;
    }

    @PayloadRoot(namespace = NS, localPart = "submitImportRequest")
    @ResponsePayload
    public Element submitImport(@RequestPayload Element req) {
        String fileName = text(req, "fileName");
        String policy = text(req, "policy");
        boolean validate = Boolean.parseBoolean(text(req, "validate"));
        byte[] bytes = Base64.getMimeDecoder().decode(text(req, "content").trim());
        ParsePolicy p = policy == null || policy.isBlank() ? ParsePolicy.STRICT : ParsePolicy.valueOf(policy.trim().toUpperCase());
        var out = imports.upload(bytes, fileName, p);
        ImportBatch b = out.batch();
        if (validate && !out.duplicate() && b.rowCount() > 0) {
            b = validation.validate(b.id()).batch();
        }
        Document doc = newDoc();
        Element res = doc.createElementNS(NS, "submitImportResponse");
        res.appendChild(batch(doc, b));
        res.appendChild(el(doc, "duplicate", String.valueOf(out.duplicate())));
        return res;
    }

    private static Element batch(Document doc, ImportBatch b) {
        Element e = doc.createElementNS(NS, "batch");
        e.appendChild(el(doc, "batchId", String.valueOf(b.id())));
        e.appendChild(el(doc, "sourceName", b.sourceName()));
        e.appendChild(el(doc, "status", b.status().name()));
        e.appendChild(el(doc, "rowCount", String.valueOf(b.rowCount())));
        e.appendChild(el(doc, "parseErrors", String.valueOf(b.parseErrors())));
        e.appendChild(el(doc, "errorCount", String.valueOf(b.errorCount())));
        if (b.postedAt() != null) e.appendChild(el(doc, "postedAt", b.postedAt().toString()));
        return e;
    }

    private static Element el(Document doc, String name, String value) {
        Element e = doc.createElementNS(NS, name);
        e.setTextContent(value);
        return e;
    }

    private static String text(Element parent, String name) {
        NodeList nl = parent.getElementsByTagNameNS(NS, name);
        return nl.getLength() == 0 ? null : nl.item(0).getTextContent();
    }

    private static Document newDoc() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            return f.newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }
}
