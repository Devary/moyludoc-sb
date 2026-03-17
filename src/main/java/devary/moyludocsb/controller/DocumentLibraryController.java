package devary.moyludocsb.controller;

import devary.moyludocsb.service.DocxHtmlRendererService;
import devary.moyludocsb.service.DocxParsingService;
import devary.moyludocsb.service.DocumentExtractionCacheService;
import devary.moyludocsb.service.DocumentLibraryHtmlService;
import devary.moyludocsb.service.DocumentLibraryService;
import devary.moyludocsb.service.PdfPreviewService;
import devary.moyludocsb.service.PresentationPreviewService;
import devary.moyludocsb.service.SpreadsheetPreviewService;
import devary.moyludocsb.service.TextPreviewService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/api/docx/library")
public class DocumentLibraryController {
    private final DocumentLibraryService documentLibraryService;
    private final DocumentLibraryHtmlService documentLibraryHtmlService;
    private final DocumentExtractionCacheService documentExtractionCacheService;
    private final DocxParsingService docxParsingService;
    private final DocxHtmlRendererService docxHtmlRendererService;
    private final SpreadsheetPreviewService spreadsheetPreviewService;
    private final PresentationPreviewService presentationPreviewService;
    private final PdfPreviewService pdfPreviewService;
    private final TextPreviewService textPreviewService;

    public DocumentLibraryController(DocumentLibraryService documentLibraryService, DocumentLibraryHtmlService documentLibraryHtmlService, DocumentExtractionCacheService documentExtractionCacheService, DocxParsingService docxParsingService, DocxHtmlRendererService docxHtmlRendererService, SpreadsheetPreviewService spreadsheetPreviewService, PresentationPreviewService presentationPreviewService, PdfPreviewService pdfPreviewService, TextPreviewService textPreviewService) {
        this.documentLibraryService = documentLibraryService;
        this.documentLibraryHtmlService = documentLibraryHtmlService;
        this.documentExtractionCacheService = documentExtractionCacheService;
        this.docxParsingService = docxParsingService;
        this.docxHtmlRendererService = docxHtmlRendererService;
        this.spreadsheetPreviewService = spreadsheetPreviewService;
        this.presentationPreviewService = presentationPreviewService;
        this.pdfPreviewService = pdfPreviewService;
        this.textPreviewService = textPreviewService;
    }

    @GetMapping("/tree")
    public ResponseEntity<DocumentLibraryService.DocumentTreeNode> tree(@RequestParam(required = false) String root, @RequestParam(required = false, defaultValue = "false") boolean reload) {
        return ResponseEntity.ok(reload ? documentLibraryService.reloadTreeInBackground(root) : documentLibraryService.loadTree(root));
    }

    @GetMapping("/tree/browser-data")
    public ResponseEntity<DocumentLibraryHtmlService.BrowserNode> browserTree(@RequestParam(required = false) String root) {
        return ResponseEntity.ok(documentLibraryHtmlService.toBrowserNode(documentLibraryService.loadTree(root)));
    }

    @GetMapping("/tree/status")
    public ResponseEntity<DocumentLibraryService.TreeReloadStatus> treeStatus(@RequestParam(required = false) String root) {
        return ResponseEntity.ok(documentLibraryService.treeReloadStatus(root));
    }

    @GetMapping("/children")
    public ResponseEntity<java.util.List<DocumentLibraryService.DocumentTreeNode>> children(@RequestParam(required = false) String id, @RequestParam(required = false) String root) {
        return ResponseEntity.ok(documentLibraryService.loadChildren(id, root));
    }

    @GetMapping("/browser")
    public String browser(@RequestParam(required = false) String root, Model model) {
        var tree = documentLibraryService.loadTree(root);
        Path rootPath = documentLibraryService.libraryRootPath(root);
        model.addAttribute("tree", documentLibraryHtmlService.toBrowserNode(tree));
        model.addAttribute("root", rootPath.toString());
        return "browser";
    }

    @GetMapping("/admin")
    public String admin(@RequestParam(required = false) String root, @RequestParam(required = false, defaultValue = "false") boolean reload, Model model) {
        Path rootPath = documentLibraryService.libraryRootPath(root);
        if (reload) {
            documentLibraryService.reloadTreeInBackground(rootPath.toString());
        }
        model.addAttribute("root", rootPath.toString());
        model.addAttribute("reload", reload);
        return "admin";
    }

    @GetMapping("/document/extract")
    public ResponseEntity<Object> extract(@RequestParam String id, @RequestParam(required = false) String root) { return ResponseEntity.ok(extractStored(readDocument(id, root))); }

    @GetMapping(value = "/document/preview", produces = MediaType.TEXT_HTML_VALUE)
    public String preview(@RequestParam String id, @RequestParam(required = false) String root, Model model) {
        var stored = readDocument(id, root);
        if (stored.empty()) { model.addAttribute("title", stored.name()); model.addAttribute("fileType", stored.fileType()); return "preview-empty"; }
        try {
            Object extracted = extractStoredCached(stored);
            switch (stored.fileType()) {
                case "docx": return renderInline(docxHtmlRendererService.renderDocument((DocxParsingService.ParsedDocx) extracted, stored.name()), model);
                case "xlsx": model.addAttribute("title", stored.name()); model.addAttribute("data", (SpreadsheetPreviewService.SpreadsheetData) extracted); return "previewSpreadsheet";
                case "pptx": model.addAttribute("title", stored.name()); model.addAttribute("data", (PresentationPreviewService.PresentationData) extracted); return "previewPresentation";
                case "pdf": model.addAttribute("title", stored.name()); model.addAttribute("data", (PdfPreviewService.PdfData) extracted); return "previewPdf";
                case "txt", "html", "md": {
                    TextPreviewService.TextData data = (TextPreviewService.TextData) extracted;
                    model.addAttribute("title", stored.name());
                    model.addAttribute("fileTypeLabel", stored.fileType().toUpperCase());
                    model.addAttribute("isHtml", "html".equals(stored.fileType()));
                    model.addAttribute("isMarkdown", "md".equals(stored.fileType()));
                    model.addAttribute("body", data.text());
                    model.addAttribute("rawBody", textPreviewService.renderBody(data));
                    return "previewText";
                }
                default: throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type");
            }
        } catch (Exception e) {
            model.addAttribute("title", stored.name());
            model.addAttribute("fileType", stored.fileType());
            return "preview-error";
        }
    }

    private String renderInline(String html, Model model) { model.addAttribute("renderedHtml", html); return "inline-rendered"; }

    @GetMapping("/document/meta")
    public ResponseEntity<DocumentMeta> meta(@RequestParam String id, @RequestParam(required = false) String root) {
        DocumentLibraryService.StoredDocument stored = readDocument(id, root);
        return ResponseEntity.ok(new DocumentMeta(stored.name(), documentLibraryService.breadcrumbsById(id, root), stored.fileType(), stored.empty(), stored.sizeInBytes()));
    }

    @GetMapping("/document/download")
    public ResponseEntity<byte[]> download(@RequestParam String id, @RequestParam(required = false) String root) {
        DocumentLibraryService.StoredDocument stored = readDocument(id, root);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(stored.name()).build().toString())
                .contentType(MediaType.parseMediaType(resolveMediaType(stored.fileType())))
                .body(stored.content());
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UploadResponse> upload(@RequestParam("file") MultipartFile file, @RequestParam(value = "folderId", required = false) String folderId, @RequestParam(value = "root", required = false) String root) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A file is required");
        String targetFolder = folderId == null || folderId.isBlank() ? "" : folderId;
        try {
            Path libraryRoot = documentLibraryService.libraryRootPath(root);
            Path folder = targetFolder.isBlank() ? libraryRoot : libraryRoot.resolve(Path.of(new String(Base64.getUrlDecoder().decode(targetFolder)))).normalize();
            Files.createDirectories(folder);
            Path destination = folder.resolve(file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename()).normalize();
            if (!destination.startsWith(libraryRoot)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid target folder");
            Files.copy(file.getInputStream(), destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            documentExtractionCacheService.invalidate(destination);
            documentLibraryService.invalidateTree(root);
            String newId = Base64.getUrlEncoder().withoutPadding().encodeToString(libraryRoot.relativize(destination).toString().getBytes());
            return ResponseEntity.ok(new UploadResponse(destination.getFileName().toString(), newId));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded file", e);
        }
    }

    private Object extractStored(DocumentLibraryService.StoredDocument stored) {
        if (stored.empty()) return new EmptyDocumentResponse(stored.name(), stored.fileType(), stored.sizeInBytes(), true);
        try { return extractStoredCached(stored); } catch (Exception e) { return new DocumentErrorResponse(stored.name(), stored.fileType(), "extract", true, "There was an error while parsing the document. Please contact Fakher Hammami."); }
    }

    private Object extractStoredCached(DocumentLibraryService.StoredDocument stored) {
        return documentExtractionCacheService.getOrCompute(stored, () -> switch (stored.fileType()) {
            case "docx" -> docxParsingService.parse(stored.content());
            case "xlsx" -> spreadsheetPreviewService.parse(stored.content());
            case "pptx" -> presentationPreviewService.parse(stored.content());
            case "pdf" -> pdfPreviewService.parse(stored.content());
            case "txt", "html", "md" -> textPreviewService.parse(stored.content(), stored.fileType());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported file type");
        });
    }

    private DocumentLibraryService.StoredDocument readDocument(String id, String root) {
        if (id == null || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Document id is required");
        try { return documentLibraryService.loadDocument(id, root); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e); }
    }

    private String resolveMediaType(String fileType) {
        return switch (fileType) {
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "pdf" -> "application/pdf";
            case "txt" -> MediaType.TEXT_PLAIN_VALUE;
            case "html" -> MediaType.TEXT_HTML_VALUE;
            case "md" -> "text/markdown";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    public record EmptyDocumentResponse(String name, String fileType, long sizeInBytes, boolean empty) {}
    public record DocumentErrorResponse(String name, String fileType, String operation, boolean error, String message) {}
    public record DocumentMeta(String name, String breadcrumbs, String fileType, boolean empty, long sizeInBytes) {}
    public record UploadResponse(String name, String id) {}
}
