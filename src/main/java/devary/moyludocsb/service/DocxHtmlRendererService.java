package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DocxHtmlRendererService {

    public String renderDocument(DocxParsingService.ParsedDocx parsedDocx, String title) {
        String safeTitle = escapeHtml(title);
        StringBuilder html = new StringBuilder();

        html.append("<!DOCTYPE html>")
                .append("<html lang=\"en\">")
                .append("<head>")
                .append("<meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>")
                .append(safeTitle)
                .append("</title>")
                .append("<style>")
                .append("body{font-family:Inter,Arial,sans-serif;background:#f5f7fb;color:#1f2937;margin:0;padding:32px;}")
                .append(".page{max-width:900px;margin:0 auto;background:#fff;padding:48px;box-shadow:0 10px 30px rgba(0,0,0,.08);border-radius:16px;}")
                .append(".doc-header{margin-bottom:24px;padding-bottom:16px;border-bottom:1px solid #e5e7eb;}")
                .append(".doc-footer{margin-top:32px;padding-top:16px;border-top:1px solid #e5e7eb;color:#6b7280;font-size:14px;}")
                .append(".meta{color:#6b7280;font-size:14px;}")
                .append("p{margin:0 0 14px;line-height:1.6;}")
                .append("table{width:100%;border-collapse:collapse;margin:16px 0;}")
                .append("td,th{border:1px solid #d1d5db;padding:10px;vertical-align:top;}")
                .append("tr:first-child td{background:#f9fafb;font-weight:600;}")
                .append(".image-placeholder{display:inline-block;padding:10px 14px;border:1px dashed #9ca3af;border-radius:8px;background:#f9fafb;color:#4b5563;margin:4px 0;}")
                .append("img.embedded-image{max-width:100%;height:auto;display:block;margin:8px 0;border-radius:8px;}")
                .append(".section-block{margin-bottom:18px;}")
                .append("a.doc-link-rendered{color:#2563eb;text-decoration:underline;}")
                .append(".list-item{display:flex;gap:10px;align-items:flex-start;margin:0 0 14px;line-height:1.6;}")
                .append(".list-bullet{min-width:22px;color:#374151;}")
                .append(".align-CENTER{text-align:center;}")
                .append(".align-RIGHT{text-align:right;}")
                .append(".align-BOTH{text-align:justify;}")
                .append(".heading1{font-size:2rem;font-weight:700;margin:0 0 18px;}")
                .append(".heading2{font-size:1.5rem;font-weight:700;margin:0 0 14px;}")
                .append(".heading3{font-size:1.2rem;font-weight:700;margin:0 0 12px;}")
                .append("</style>")
                .append("</head>")
                .append("<body>")
                .append("<div class=\"page\">")
                .append("<div class=\"doc-header\"><h1>")
                .append(safeTitle)
                .append("</h1><div class=\"meta\">Rendered from extracted DOCX structure</div></div>");

        appendSections(html, "Headers", parsedDocx.headers(), true);
        appendContent(html, parsedDocx.orderedContent());
        appendSections(html, "Footers", parsedDocx.footers(), false);

        html.append("</div></body></html>");
        return html.toString();
    }

    private void appendSections(StringBuilder html, String title, List<DocxParsingService.SectionContent> sections, boolean header) {
        if (sections == null || sections.isEmpty()) {
            return;
        }
        html.append("<div class=\"").append(header ? "doc-header" : "doc-footer").append("\">");
        html.append("<div class=\"meta\">").append(escapeHtml(title)).append("</div>");
        for (DocxParsingService.SectionContent section : sections) {
            html.append("<div class=\"section-block\">");
            appendContent(html, section.content());
            html.append("</div>");
        }
        html.append("</div>");
    }

    private void appendContent(StringBuilder html, List<DocxParsingService.DocumentComponent> content) {
        if (content == null) {
            return;
        }
        for (DocxParsingService.DocumentComponent component : content) {
            if (component.type() == DocxParsingService.ComponentType.PARAGRAPH) {
                appendParagraph(html, component);
            } else if (component.type() == DocxParsingService.ComponentType.TABLE) {
                appendTable(html, component.table());
            } else if (component.type() == DocxParsingService.ComponentType.IMAGE) {
                appendImage(html, component.image());
            }
        }
    }

    private void appendParagraph(StringBuilder html, DocxParsingService.DocumentComponent component) {
        DocxParsingService.ParagraphData paragraph = component.paragraph();
        String styleClass = resolveParagraphClass(component.style());
        String alignClass = component.alignment() != null ? " align-" + component.alignment() : "";
        String inlineStyle = buildParagraphStyle(paragraph);

        if (paragraph != null && paragraph.listItem()) {
            html.append("<div class=\"list-item").append(alignClass).append("\" style=\"").append(inlineStyle).append("\">");
            html.append("<div class=\"list-bullet\">").append(resolveBullet(paragraph.listLevel())).append("</div>");
            html.append("<div>");
            appendInlineComponents(html, paragraph.components());
            html.append("</div></div>");
            return;
        }

        html.append("<p class=\"").append(styleClass).append(alignClass).append("\" style=\"").append(inlineStyle).append("\">");
        if (paragraph != null) {
            appendInlineComponents(html, paragraph.components());
        } else {
            html.append(escapeHtml(component.text()));
        }
        html.append("</p>");
    }

    private void appendInlineComponents(StringBuilder html, List<DocxParsingService.InlineComponent> components) {
        if (components == null) {
            return;
        }
        for (DocxParsingService.InlineComponent component : components) {
            if (component.type() == DocxParsingService.ComponentType.TEXT) {
                html.append("<span style=\"").append(buildTextStyle(component.textStyle())).append("\">")
                        .append(escapeHtml(component.text()))
                        .append("</span>");
            } else if (component.type() == DocxParsingService.ComponentType.LINK && component.hyperlink() != null) {
                html.append("<a class=\"doc-link-rendered\" href=\"")
                        .append(escapeHtml(component.hyperlink().url()))
                        .append("\" target=\"_blank\" rel=\"noopener noreferrer\" style=\"")
                        .append(buildTextStyle(component.textStyle()))
                        .append("\">")
                        .append(escapeHtml(component.text()))
                        .append("</a>");
            } else if (component.type() == DocxParsingService.ComponentType.IMAGE) {
                appendImage(html, component.image());
            } else if (component.type() == DocxParsingService.ComponentType.TABLE && component.table() != null) {
                appendTable(html, component.table());
            }
        }
    }

    private void appendTable(StringBuilder html, DocxParsingService.TableData table) {
        if (table == null) {
            return;
        }
        html.append("<table>");
        for (DocxParsingService.TableRowData row : table.rows()) {
            html.append("<tr>");
            for (DocxParsingService.TableCellData cell : row.cells()) {
                html.append("<td>");
                if (cell.content() != null && !cell.content().isEmpty()) {
                    appendContent(html, cell.content());
                } else {
                    html.append(escapeHtml(cell.text()));
                }
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
    }

    private void appendImage(StringBuilder html, DocxParsingService.ImageData image) {
        if (image == null) {
            return;
        }
        if (image.base64Data() != null && !image.base64Data().isBlank() && image.mimeType() != null && !image.mimeType().isBlank()) {
            html.append("<figure>")
                    .append("<img class=\"embedded-image\" alt=\"")
                    .append(escapeHtml(firstNonBlank(image.description(), image.fileName(), "Embedded image")))
                    .append("\" src=\"data:")
                    .append(escapeHtml(image.mimeType()))
                    .append(";base64,")
                    .append(image.base64Data())
                    .append("\">");
            if (!firstNonBlank(image.description(), image.fileName()).isBlank()) {
                html.append("<figcaption class=\"meta\">")
                        .append(escapeHtml(firstNonBlank(image.description(), image.fileName())))
                        .append("</figcaption>");
            }
            html.append("</figure>");
            return;
        }
        html.append("<div class=\"image-placeholder\">🖼 ")
                .append(escapeHtml(firstNonBlank(image.description(), image.fileName(), "Embedded image")))
                .append("</div>");
    }

    private String buildParagraphStyle(DocxParsingService.ParagraphData paragraph) {
        if (paragraph == null) {
            return "";
        }
        StringBuilder style = new StringBuilder();
        if (paragraph.indentationLeft() > 0) {
            style.append("margin-left:").append(paragraph.indentationLeft() / 20.0).append("pt;");
        }
        if (paragraph.firstLineIndentation() > 0) {
            style.append("text-indent:").append(paragraph.firstLineIndentation() / 20.0).append("pt;");
        }
        return style.toString();
    }

    private String buildTextStyle(DocxParsingService.TextStyle style) {
        if (style == null) {
            return "";
        }
        StringBuilder css = new StringBuilder();
        if (style.bold()) {
            css.append("font-weight:bold;");
        }
        if (style.italic()) {
            css.append("font-style:italic;");
        }
        boolean underline = style.underline() != null && !style.underline().isBlank() && !"NONE".equals(style.underline());
        if (style.strikeThrough() && underline) {
            css.append("text-decoration:underline line-through;");
        } else if (style.strikeThrough()) {
            css.append("text-decoration:line-through;");
        } else if (underline) {
            css.append("text-decoration:underline;");
        }
        if (style.color() != null && !style.color().isBlank()) {
            css.append("color:#").append(style.color()).append(";");
        }
        if (style.fontFamily() != null && !style.fontFamily().isBlank()) {
            css.append("font-family:").append(style.fontFamily()).append(";");
        }
        if (style.fontSize() != null && style.fontSize() > 0) {
            css.append("font-size:").append(style.fontSize()).append("pt;");
        }
        return css.toString();
    }

    private String resolveParagraphClass(String style) {
        if (style == null) {
            return "";
        }
        return switch (style.toLowerCase()) {
            case "heading1" -> "heading1";
            case "heading2" -> "heading2";
            case "heading3" -> "heading3";
            default -> "";
        };
    }

    private String resolveBullet(String listLevel) {
        if (listLevel == null || "0".equals(listLevel)) {
            return "•";
        }
        return switch (listLevel) {
            case "1" -> "◦";
            case "2" -> "▪";
            default -> "–";
        };
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
                .replace("\n", "<br>");
    }
}
