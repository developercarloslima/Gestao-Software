package br.com.nh.cotacao.service;

import com.lowagie.text.Document;
import com.lowagie.text.pdf.PdfCopy;
import com.lowagie.text.pdf.PdfReader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * Acrescenta a capa e a página final configuradas pelo Admin sem alterar o
 * conteúdo central do relatório/dossiê. Os dois arquivos são opcionais e
 * armazenados em site_documents.
 */
@Service
public class ReportEnvelopeService {
    private final SiteDocumentService documents;

    public ReportEnvelopeService(SiteDocumentService documents) {
        this.documents = documents;
    }

    public byte[] decorate(byte[] mainPdf) {
        if (mainPdf == null || mainPdf.length == 0) return mainPdf;
        SiteDocumentService.StoredDocument cover = documents.reportCoverFile();
        SiteDocumentService.StoredDocument ending = documents.reportEndingFile();
        if (cover == null && ending == null) return mainPdf;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfReader mainReader = new PdfReader(mainPdf);
            java.util.Map<String,String> info = mainReader.getInfo();
            mainReader.close();
            Document document = new Document();
            if (info.get("Title") != null) document.addTitle(info.get("Title"));
            if (info.get("Author") != null) document.addAuthor(info.get("Author"));
            if (info.get("Subject") != null) document.addSubject(info.get("Subject"));
            if (info.get("Keywords") != null) document.addKeywords(info.get("Keywords"));
            PdfCopy copy = new PdfCopy(document, output);
            document.open();
            append(copy, cover == null ? null : cover.bytes());
            append(copy, mainPdf);
            append(copy, ending == null ? null : ending.bytes());
            document.close();
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível aplicar a capa/página final ao relatório.", exception);
        }
    }

    private void append(PdfCopy copy, byte[] bytes) throws Exception {
        if (bytes == null || bytes.length == 0) return;
        PdfReader reader = new PdfReader(bytes);
        try {
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                copy.addPage(copy.getImportedPage(reader, page));
            }
        } finally {
            copy.freeReader(reader);
            reader.close();
        }
    }
}
