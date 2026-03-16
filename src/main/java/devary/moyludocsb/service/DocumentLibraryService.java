package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;

@Service
public class DocumentLibraryService {

    @Value("${moyludoc.library.root:docs-library}")
    String libraryRoot;

    public DocumentTreeNode loadTree() {
        Path root = ensureRoot();
        return new DocumentTreeNode(
                root.getFileName().toString(),
                "",
                false,
                encode(Path.of("")),
                "directory",
                true,
                false,
                0,
                buildFullChildren(root),
                true);
    }

    public List<DocumentTreeNode> loadChildren(String id) {
        Path folder = resolveFromId(id, true);
        return loadChildrenInternal(folder);
    }

    public StoredDocument loadDocument(String id) {
        Path path = resolveFromId(id, false);
        try {
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new StoredDocument(
                    path,
                    path.getFileName().toString(),
                    detectFileType(path),
                    size == 0,
                    size,
                    size == 0 ? new byte[0] : Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read document", e);
        }
    }

    public String titleById(String id) {
        return resolveFromId(id, false).getFileName().toString();
    }

    public String breadcrumbsById(String id) {
        Path relative = decode(id);
        List<String> parts = new ArrayList<>();
        for (Path part : relative) {
            parts.add(part.toString());
        }
        return String.join(" / ", parts);
    }

    private List<DocumentTreeNode> loadChildrenInternal(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .map(path -> toNode(directory, path))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load children for " + directory, e);
        }
    }

    private List<DocumentTreeNode> buildFullChildren(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .map(this::toFullNode)
                    .filter(Objects::nonNull)
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build full tree for " + directory, e);
        }
    }

    private DocumentTreeNode toNode(Path parent, Path path) {
        try {
            Path root = ensureRoot();
            Path relative = root.relativize(path);
            String id = encode(relative);
            if (Files.isDirectory(path)) {
                boolean hasVisibleChildren = hasVisibleChildren(path);
                if (!hasVisibleChildren) {
                    return null;
                }
                return new DocumentTreeNode(
                        path.getFileName().toString(),
                        relative.toString(),
                        false,
                        id,
                        "directory",
                        true,
                        false,
                        0,
                        List.of(),
                        false);
            }
            if (!isSupportedDocument(path)) {
                return null;
            }
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new DocumentTreeNode(
                    path.getFileName().toString(),
                    relative.toString(),
                    true,
                    id,
                    detectFileType(path),
                    false,
                    size == 0,
                    size,
                    List.of(),
                    true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private DocumentTreeNode toFullNode(Path path) {
        try {
            Path root = ensureRoot();
            Path relative = root.relativize(path);
            String id = encode(relative);
            if (Files.isDirectory(path)) {
                List<DocumentTreeNode> children = buildFullChildren(path);
                if (children.isEmpty()) {
                    return null;
                }
                return new DocumentTreeNode(
                        path.getFileName().toString(),
                        relative.toString(),
                        false,
                        id,
                        "directory",
                        true,
                        false,
                        0,
                        children,
                        true);
            }
            if (!isSupportedDocument(path)) {
                return null;
            }
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new DocumentTreeNode(
                    path.getFileName().toString(),
                    relative.toString(),
                    true,
                    id,
                    detectFileType(path),
                    false,
                    size == 0,
                    size,
                    List.of(),
                    true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean hasVisibleChildren(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(path -> {
                try {
                    if (Files.isDirectory(path)) {
                        return hasVisibleChildren(path);
                    }
                    return isSupportedDocument(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw e;
        }
    }

    private boolean isSupportedDocument(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".docx") || fileName.endsWith(".xlsx") || fileName.endsWith(".pptx")
                || fileName.endsWith(".pdf") || fileName.endsWith(".txt") || fileName.endsWith(".html")
                || fileName.endsWith(".htm") || fileName.endsWith(".md");
    }

    private String detectFileType(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".docx")) {
            return "docx";
        }
        if (fileName.endsWith(".xlsx")) {
            return "xlsx";
        }
        if (fileName.endsWith(".pptx")) {
            return "pptx";
        }
        if (fileName.endsWith(".pdf")) {
            return "pdf";
        }
        if (fileName.endsWith(".txt")) {
            return "txt";
        }
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return "html";
        }
        if (fileName.endsWith(".md")) {
            return "md";
        }
        return "unknown";
    }

    public Path libraryRootPath() {
        return ensureRoot();
    }

    private Path ensureRoot() {
        try {
            Path root = Path.of(libraryRoot).toAbsolutePath().normalize();
            if (!Files.exists(root)) {
                Files.createDirectories(root);
            }
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize library root", e);
        }
    }

    private Path resolveFromId(String id, boolean expectDirectory) {
        if (id == null || id.isBlank()) {
            return ensureRoot();
        }
        Path root = ensureRoot();
        Path relative = decode(id);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes library root");
        }
        if (!Files.exists(resolved)) {
            throw new IllegalArgumentException("Document not found");
        }
        if (expectDirectory && !Files.isDirectory(resolved)) {
            throw new IllegalArgumentException("Folder not found");
        }
        if (!expectDirectory && !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Document not found");
        }
        return resolved;
    }

    private String encode(Path relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Path decode(String id) {
        if (id == null || id.isBlank()) {
            return Path.of("");
        }
        return Path.of(new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8));
    }

    public record DocumentTreeNode(
            String name,
            String relativePath,
            boolean document,
            String id,
            String fileType,
            boolean expandable,
            boolean empty,
            long sizeInBytes,
            List<DocumentTreeNode> children,
            boolean loaded) {

        public DocumentTreeNode {
            children = List.copyOf(Objects.requireNonNullElse(children, List.of()));
        }
    }

    public record StoredDocument(
            Path path,
            String name,
            String fileType,
            boolean empty,
            long sizeInBytes,
            byte[] content) {
    }
}
