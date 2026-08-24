package io.ledgerlift.template;

/** The file itself could not be read as a template (bad zip, no csv inside). */
@org.springframework.ws.soap.server.endpoint.annotation.SoapFault(faultCode = org.springframework.ws.soap.server.endpoint.annotation.FaultCode.CLIENT)
public class TemplateException extends RuntimeException {
    public TemplateException(String message) { super(message); }
    public TemplateException(String message, Throwable cause) { super(message, cause); }
}
