package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;

@Service
public class TextPreviewService {

    public TextData parse(byte[] content, String fileType) {
        String text = new String(content, StandardCharsets.UTF_8);
        return new TextData(fileType, text);
    }

    public String renderBody(TextData data) {
        return switch (data.fileType()) {
            case "html" -> data.text();
            case "md" -> renderMarkdown(data.text());
            default -> data.text();
        };
    }

    private String renderMarkdown(String markdown) {
        StringBuilder html = new StringBuilder();
        for (String line : markdown.split("\\R")) {
            if (line.startsWith("### ")) html.append("<h3>").append(escape(line.substring(4))).append("</h3>");
            else if (line.startsWith("## ")) html.append("<h2>").append(escape(line.substring(3))).append("</h2>");
            else if (line.startsWith("# ")) html.append("<h1>").append(escape(line.substring(2))).append("</h1>");
            else if (line.startsWith("- ")) html.append("<li>").append(escape(line.substring(2))).append("</li>");
            else if (line.isBlank()) html.append("");
            else html.append("<p>").append(escape(line)).append("</p>");
        }
        return html.toString().replaceAll("(?s)(<li>.*?</li>)", "<ul>$1</ul>");
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public record TextData(String fileType, String text) {}
}
