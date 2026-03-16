package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;

@Service
public class PdfPreviewService {

    @Value("${moyludoc.pdf.preview.dpi:120}")
    int previewDpi;

    public PdfData parse(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            PDFRenderer renderer = new PDFRenderer(document);
            List<PageImage> pageImages = new ArrayList<>();
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                try {
                    BufferedImage image = renderer.renderImageWithDPI(i, previewDpi, ImageType.RGB);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ImageIO.write(image, "png", out);
                    pageImages.add(new PageImage(i + 1, Base64.getEncoder().encodeToString(out.toByteArray())));
                } catch (RuntimeException e) {
                    // Keep text extraction working even when thumbnail rendering fails for a page.
                }
            }
            return new PdfData(document.getNumberOfPages(), text == null ? "" : text.trim(), pageImages);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse PDF", e);
        }
    }

    public String renderHtml(PdfData data, String title) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>").append(escape(title)).append("</title>")
                .append("<style>body{font-family:Inter,Arial,sans-serif;background:#f8fafc;color:#0f172a;padding:32px}.page{max-width:980px;margin:0 auto;background:#fff;padding:32px;border-radius:16px;box-shadow:0 10px 30px rgba(0,0,0,.08)}pre{white-space:pre-wrap;word-break:break-word;background:#f8fafc;padding:16px;border-radius:12px;border:1px solid #e2e8f0}.meta{color:#64748b}.page-thumb{max-width:100%;border:1px solid #cbd5e1;border-radius:10px;margin:14px 0}</style></head><body><div class=\"page\"><h1>")
                .append(escape(title)).append("</h1><div class=\"meta\">PDF preview • ").append(data.pageCount()).append(" pages</div>");
        for (PageImage image : data.pageImages()) {
            html.append("<div class=\"meta\">Page ").append(image.pageNumber()).append("</div>")
                    .append("<img class=\"page-thumb\" alt=\"PDF page\" src=\"data:image/png;base64,")
                    .append(image.base64Png()).append("\">");
        }
        html.append("</div></body></html>");
        return html.toString();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record PdfData(int pageCount, String text, List<PageImage> pageImages) {}
    public record PageImage(int pageNumber, String base64Png) {}
}
