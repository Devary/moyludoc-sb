package devary.moyludocsb.controller;

import devary.moyludocsb.service.DocxHtmlRendererService;
import devary.moyludocsb.service.DocxParsingService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/docx")
public class DocxParsingController {
    private static final List<String> SAMPLE_FILES = List.of(
            "sample-basic.docx",
            "sample-list-and-table.docx",
            "sample-styles-showcase.docx",
            "sample-links-and-images.docx");

    private final DocxParsingService docxParsingService;
    private final DocxHtmlRendererService docxHtmlRendererService;

    public DocxParsingController(DocxParsingService docxParsingService, DocxHtmlRendererService docxHtmlRendererService) {
        this.docxParsingService = docxParsingService;
        this.docxHtmlRendererService = docxHtmlRendererService;
    }

    @GetMapping("/samples")
    public List<String> samples() { return SAMPLE_FILES; }

    @GetMapping("/samples/{fileName}/extract")
    public DocxParsingService.ParsedDocx extractSample(@PathVariable String fileName) { return docxParsingService.parse(readSample(fileName)); }

    @GetMapping(value = "/samples/{fileName}/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String previewSample(@PathVariable String fileName) {
        return docxHtmlRendererService.renderDocument(docxParsingService.parse(readSample(fileName)), fileName);
    }

    @PostMapping(value = "/extract", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DocxParsingService.ParsedDocx extract(@RequestParam("file") MultipartFile file) { return docxParsingService.parse(readUpload(file)); }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@RequestParam("file") MultipartFile file) {
        byte[] content = readUpload(file);
        return docxHtmlRendererService.renderDocument(docxParsingService.parse(content), file.getOriginalFilename() == null ? "Uploaded DOCX" : file.getOriginalFilename());
    }

    private byte[] readSample(String fileName) {
        if (!SAMPLE_FILES.contains(fileName)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown sample file: " + fileName);
        try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("samples/" + fileName)) {
            if (inputStream == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sample file not found in resources: " + fileName);
            return inputStream.readAllBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read sample DOCX: " + fileName, e);
        }
    }

    private byte[] readUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A DOCX file is required in form field 'file'");
        try { return file.getBytes(); } catch (IOException e) { throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read uploaded DOCX file", e); }
    }
}
