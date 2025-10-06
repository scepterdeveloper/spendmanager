package com.everrich.spendmanager.service; // Use service package or a utility package

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import java.io.IOException;

@Component // Using @Component or @Service here is fine
public class PdfProcessor {
    public String extractTextFromPdf(byte[] fileBytes) throws IOException {
        // We use Loader.loadPDF(byte[]) directly
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }
}