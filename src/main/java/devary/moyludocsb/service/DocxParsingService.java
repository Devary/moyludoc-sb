package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFHyperlinkRun;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFPicture;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

@Service
public class DocxParsingService {

    public ParsedDocx parse(byte[] docxContent) {
        validate(docxContent);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxContent))) {
            List<DocumentComponent> orderedContent = extractOrderedContent(document.getBodyElements());
            return new ParsedDocx(
                    extractPlainText(orderedContent),
                    orderedContent,
                    extractSectionContent(document.getHeaderList()),
                    extractSectionContent(document.getFooterList()));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse DOCX content", e);
        }
    }

    public String extractPlainText(byte[] docxContent) {
        return parse(docxContent).plainText();
    }

    public List<DocumentComponent> extractOrderedContent(byte[] docxContent) {
        validate(docxContent);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxContent))) {
            return extractOrderedContent(document.getBodyElements());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract ordered content from DOCX", e);
        }
    }

    public List<SectionContent> extractHeaders(byte[] docxContent) {
        validate(docxContent);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxContent))) {
            return extractSectionContent(document.getHeaderList());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract headers from DOCX", e);
        }
    }

    public List<SectionContent> extractFooters(byte[] docxContent) {
        validate(docxContent);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docxContent))) {
            return extractSectionContent(document.getFooterList());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to extract footers from DOCX", e);
        }
    }

    private List<DocumentComponent> extractOrderedContent(List<IBodyElement> bodyElements) {
        List<DocumentComponent> components = new ArrayList<>();
        int index = 0;

        for (IBodyElement element : bodyElements) {
            if (element instanceof XWPFParagraph paragraph) {
                components.add(toParagraphComponent(index++, paragraph));
            } else if (element instanceof XWPFTable table) {
                components.add(toTableComponent(index++, table));
            }
        }

        return components;
    }

    private DocumentComponent toParagraphComponent(int index, XWPFParagraph paragraph) {
        List<InlineComponent> inlineComponents = extractInlineComponents(paragraph);
        return new DocumentComponent(
                index,
                ComponentType.PARAGRAPH,
                paragraph.getText(),
                paragraph.getStyle(),
                paragraph.getAlignment() != null ? paragraph.getAlignment().name() : null,
                new ParagraphData(
                        paragraph.getNumID() != null,
                        paragraph.getNumID() != null ? paragraph.getNumID().toString() : null,
                        paragraph.getNumIlvl() != null ? paragraph.getNumIlvl().toString() : null,
                        paragraph.getIndentationLeft(),
                        paragraph.getIndentationRight(),
                        paragraph.getIndentationFirstLine(),
                        inlineComponents),
                null,
                null);
    }

    private List<InlineComponent> extractInlineComponents(XWPFParagraph paragraph) {
        List<InlineComponent> components = new ArrayList<>();
        int index = 0;

        for (XWPFRun run : paragraph.getRuns()) {
            String runText = extractRunText(run);
            if (runText != null && !runText.isBlank()) {
                HyperlinkData hyperlink = null;
                if (run instanceof XWPFHyperlinkRun hyperlinkRun) {
                    String url = null;
                    String hyperlinkId = hyperlinkRun.getHyperlinkId();
                    if (hyperlinkId != null && !hyperlinkId.isBlank()) {
                        var hyperlinkDoc = documentSafe(paragraph).getHyperlinkByID(hyperlinkId);
                        url = hyperlinkDoc != null ? hyperlinkDoc.getURL() : null;
                    }
                    if (url != null && !url.isBlank()) {
                        hyperlink = new HyperlinkData(url, runText);
                    }
                }
                components.add(new InlineComponent(
                        index++,
                        hyperlink != null ? ComponentType.LINK : ComponentType.TEXT,
                        runText,
                        new TextStyle(
                                run.isBold(),
                                run.isItalic(),
                                run.isStrikeThrough(),
                                run.isCapitalized(),
                                run.getUnderline() != null ? run.getUnderline().name() : null,
                                run.getColor(),
                                run.getFontFamily(),
                                run.getFontSizeAsDouble(),
                                run.getStyle(),
                                run.getVerticalAlignment() != null ? run.getVerticalAlignment().toString() : null),
                        null,
                        null,
                        hyperlink));
            }

            for (XWPFPicture picture : run.getEmbeddedPictures()) {
                components.add(new InlineComponent(
                        index++,
                        ComponentType.IMAGE,
                        picture.getDescription(),
                        null,
                        toImageData(picture),
                        null,
                        null));
            }
        }

        return components;
    }

    private XWPFDocument documentSafe(XWPFParagraph paragraph) {
        return paragraph.getDocument();
    }

    private ImageData toImageData(XWPFPicture picture) {
        byte[] data = picture.getPictureData() != null ? picture.getPictureData().getData() : new byte[0];
        String format = safeString(picture.getPictureData() != null ? picture.getPictureData().suggestFileExtension() : null);
        String mimeType = format.isBlank() ? "application/octet-stream" : "image/" + format;
        return new ImageData(
                safeString(picture.getDescription()),
                safeString(picture.getPictureData() != null ? picture.getPictureData().getFileName() : null),
                format,
                mimeType,
                data.length,
                data.length == 0 ? "" : Base64.getEncoder().encodeToString(data));
    }

    private DocumentComponent toTableComponent(int index, XWPFTable table) {
        List<TableRowData> rows = new ArrayList<>();
        int rowIndex = 0;

        for (XWPFTableRow row : table.getRows()) {
            List<TableCellData> cells = new ArrayList<>();
            int cellIndex = 0;

            for (XWPFTableCell cell : row.getTableCells()) {
                List<DocumentComponent> cellContent = extractOrderedContent(cell.getBodyElements());
                cells.add(new TableCellData(cellIndex++, cell.getText(), cellContent));
            }

            rows.add(new TableRowData(rowIndex++, cells));
        }

        return new DocumentComponent(
                index,
                ComponentType.TABLE,
                extractTableText(table),
                null,
                null,
                null,
                new TableData(rows),
                null);
    }

    private List<SectionContent> extractSectionContent(List<? extends Object> sections) {
        List<SectionContent> result = new ArrayList<>();
        int index = 0;

        for (Object section : sections) {
            if (section instanceof XWPFHeader header) {
                result.add(new SectionContent(index++, extractOrderedContent(header.getBodyElements())));
            } else if (section instanceof XWPFFooter footer) {
                result.add(new SectionContent(index++, extractOrderedContent(footer.getBodyElements())));
            }
        }

        return result;
    }

    private String extractRunText(XWPFRun run) {
        String text = run.text();
        if (text != null) {
            return text;
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; ; i++) {
            String part = run.getText(i);
            if (part == null) {
                break;
            }
            builder.append(part);
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private String extractPlainText(List<DocumentComponent> components) {
        StringBuilder text = new StringBuilder();
        for (DocumentComponent component : components) {
            appendComponentText(text, component);
        }
        return text.toString().trim();
    }

    private void appendComponentText(StringBuilder text, DocumentComponent component) {
        if (component == null) {
            return;
        }

        if (component.type() == ComponentType.PARAGRAPH && component.paragraph() != null) {
            appendLine(text, component.text());
            return;
        }

        if (component.type() == ComponentType.TABLE && component.table() != null) {
            appendLine(text, component.text());
            return;
        }

        if (component.type() == ComponentType.IMAGE && component.image() != null) {
            appendLine(text, "[IMAGE] " + safeString(component.image().fileName()));
        }
    }

    private String extractTableText(XWPFTable table) {
        StringBuilder text = new StringBuilder();

        for (XWPFTableRow row : table.getRows()) {
            List<String> cellTexts = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cellTexts.add(cell.getText());
            }
            appendLine(text, String.join(" | ", cellTexts));
        }

        return text.toString().trim();
    }

    private void appendLine(StringBuilder text, String value) {
        if (value == null || value.isBlank()) {
            return;
        }

        if (!text.isEmpty()) {
            text.append(System.lineSeparator());
        }
        text.append(value.trim());
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private void validate(byte[] docxContent) {
        if (docxContent == null || docxContent.length == 0) {
            throw new IllegalArgumentException("docxContent must not be null or empty");
        }
    }

    public enum ComponentType {
        PARAGRAPH,
        TEXT,
        LINK,
        TABLE,
        IMAGE
    }

    public record ParsedDocx(
            String plainText,
            List<DocumentComponent> orderedContent,
            List<SectionContent> headers,
            List<SectionContent> footers) {
    }

    public record DocumentComponent(
            int index,
            ComponentType type,
            String text,
            String style,
            String alignment,
            ParagraphData paragraph,
            TableData table,
            ImageData image) {
    }

    public record ParagraphData(
            boolean listItem,
            String listId,
            String listLevel,
            int indentationLeft,
            int indentationRight,
            int firstLineIndentation,
            List<InlineComponent> components) {
    }

    public record InlineComponent(
            int index,
            ComponentType type,
            String text,
            TextStyle textStyle,
            ImageData image,
            TableData table,
            HyperlinkData hyperlink) {
    }

    public record TextStyle(
            boolean bold,
            boolean italic,
            boolean strikeThrough,
            boolean capitalized,
            String underline,
            String color,
            String fontFamily,
            Double fontSize,
            String style,
            String verticalAlignment) {
    }

    public record HyperlinkData(String url, String label) {
    }

    public record ImageData(
            String description,
            String fileName,
            String format,
            String mimeType,
            int sizeInBytes,
            String base64Data) {
    }

    public record TableData(List<TableRowData> rows) {
    }

    public record TableRowData(int index, List<TableCellData> cells) {
    }

    public record TableCellData(int index, String text, List<DocumentComponent> content) {
    }

    public record SectionContent(int index, List<DocumentComponent> content) {
    }
}
