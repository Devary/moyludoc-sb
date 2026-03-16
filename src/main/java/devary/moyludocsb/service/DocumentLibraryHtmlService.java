package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DocumentLibraryHtmlService {

    public BrowserNode toBrowserNode(DocumentLibraryService.DocumentTreeNode node) {
        return new BrowserNode(
                node.name(),
                node.relativePath(),
                node.document(),
                node.id(),
                node.fileType(),
                node.empty(),
                iconFor(node.fileType()),
                node.children().stream().map(this::toBrowserNode).toList());
    }

    private String iconFor(String type) {
        if (type == null) {
            return "📄";
        }
        return switch (type) {
            case "xlsx" -> "📊";
            case "pptx" -> "📽️";
            case "pdf" -> "📕";
            case "html" -> "🌐";
            case "md" -> "📝";
            case "txt" -> "📄";
            default -> "📄";
        };
    }

    public record BrowserNode(
            String name,
            String relativePath,
            boolean document,
            String id,
            String fileType,
            boolean empty,
            String icon,
            List<BrowserNode> children) {
    }
}
