package devary.moyludocsb.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class DocumentLibraryService {
    private static final Logger log = LoggerFactory.getLogger(DocumentLibraryService.class);

    @Value("${moyludoc.library.root:docs-library}")
    String libraryRoot;

    private final Map<Path, CacheEntry> treeCache = new ConcurrentHashMap<>();

    @PostConstruct
    void warmUpDefaultTreeCache() {
        Path root = ensureRoot(null);
        CacheEntry entry = treeCache.computeIfAbsent(root, this::newCacheEntry);
        buildTreeSynchronously(entry);
    }

    public DocumentTreeNode loadTree() {
        return loadTree(null);
    }

    public DocumentTreeNode loadTree(String requestedRoot) {
        Path root = ensureRoot(requestedRoot);
        CacheEntry entry = treeCache.computeIfAbsent(root, this::newCacheEntry);
        if (entry.version().get() == 0 && !entry.reloading()) {
            buildTreeSynchronously(entry);
        }
        return snapshot(entry);
    }

    public DocumentTreeNode reloadTreeInBackground(String requestedRoot) {
        Path root = ensureRoot(requestedRoot);
        CacheEntry entry = treeCache.computeIfAbsent(root, this::newCacheEntry);
        startBackgroundReload(entry);
        return snapshot(entry);
    }

    public TreeReloadStatus treeReloadStatus(String requestedRoot) {
        Path root = ensureRoot(requestedRoot);
        CacheEntry entry = treeCache.computeIfAbsent(root, this::newCacheEntry);
        return new TreeReloadStatus(
                root.toString(),
                entry.reloading(),
                entry.reloadStartedAt(),
                entry.lastUpdatedAt(),
                entry.version().get(),
                Math.max(0, System.currentTimeMillis() - entry.reloadStartedAt()));
    }

    public List<DocumentTreeNode> loadChildren(String id) {
        return loadChildren(id, null);
    }

    public List<DocumentTreeNode> loadChildren(String id, String requestedRoot) {
        Path folder = resolveFromId(id, true, requestedRoot);
        return loadChildrenInternal(folder, requestedRoot);
    }

    public StoredDocument loadDocument(String id) {
        return loadDocument(id, null);
    }

    public StoredDocument loadDocument(String id, String requestedRoot) {
        Path path = resolveFromId(id, false, requestedRoot);
        try {
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new StoredDocument(path, path.getFileName().toString(), detectFileType(path), size == 0, size, size == 0 ? new byte[0] : Files.readAllBytes(path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read document", e);
        }
    }

    public String titleById(String id) {
        return titleById(id, null);
    }

    public String titleById(String id, String requestedRoot) {
        return resolveFromId(id, false, requestedRoot).getFileName().toString();
    }

    public String breadcrumbsById(String id) {
        return breadcrumbsById(id, null);
    }

    public String breadcrumbsById(String id, String requestedRoot) {
        Path relative = decode(id);
        List<String> parts = new ArrayList<>();
        Path root = ensureRoot(requestedRoot);
        parts.add(root.getFileName() == null ? root.toString() : root.getFileName().toString());
        for (Path part : relative) {
            parts.add(part.toString());
        }
        return String.join(" / ", parts);
    }

    public Path libraryRootPath() {
        return libraryRootPath(null);
    }

    public Path libraryRootPath(String requestedRoot) {
        return ensureRoot(requestedRoot);
    }

    public void invalidateTree(String requestedRoot) {
        treeCache.remove(ensureRoot(requestedRoot));
    }

    private CacheEntry newCacheEntry(Path root) {
        return new CacheEntry(root, new MutableTreeNode(displayName(root), "", false, encode(Path.of("")), "directory", true, false, 0), new AtomicLong(), false, 0L, 0L);
    }

    private void buildTreeSynchronously(CacheEntry entry) {
        beginReload(entry);
        try {
            log.info("Tree extraction in progress for root: {}", entry.root());
            MutableTreeNode newRoot = new MutableTreeNode(displayName(entry.root()), "", false, encode(Path.of("")), "directory", true, false, 0);
            buildChildrenLive(entry.root(), entry.root(), newRoot, entry);
            finishReload(entry, newRoot);
            log.info("Tree extraction ended for root: {}", entry.root());
        } catch (Exception e) {
            failReload(entry, e);
            throw e;
        }
    }

    private void startBackgroundReload(CacheEntry entry) {
        synchronized (entry.monitor()) {
            if (entry.reloading()) {
                return;
            }
            entry.reloading(true);
            entry.reloadStartedAt(System.currentTimeMillis());
            entry.lastUpdatedAt(entry.reloadStartedAt());
            entry.version().incrementAndGet();
        }
        Thread.startVirtualThread(() -> {
            try {
                log.info("Tree extraction in progress for root: {}", entry.root());
                MutableTreeNode liveRoot = new MutableTreeNode(displayName(entry.root()), "", false, encode(Path.of("")), "directory", true, false, 0);
                synchronized (entry.monitor()) {
                    entry.rootNode(liveRoot);
                    entry.lastUpdatedAt(System.currentTimeMillis());
                    entry.version().incrementAndGet();
                }
                buildChildrenLive(entry.root(), entry.root(), liveRoot, entry);
                synchronized (entry.monitor()) {
                    entry.reloading(false);
                    entry.lastUpdatedAt(System.currentTimeMillis());
                    entry.version().incrementAndGet();
                }
                log.info("Tree extraction ended for root: {}", entry.root());
            } catch (Exception e) {
                failReload(entry, e);
                log.error("Background tree reload failed for root: {}", entry.root(), e);
            }
        });
    }

    private void beginReload(CacheEntry entry) {
        synchronized (entry.monitor()) {
            entry.reloading(true);
            entry.reloadStartedAt(System.currentTimeMillis());
            entry.lastUpdatedAt(entry.reloadStartedAt());
            entry.version().incrementAndGet();
        }
    }

    private void finishReload(CacheEntry entry, MutableTreeNode newRoot) {
        synchronized (entry.monitor()) {
            entry.rootNode(newRoot);
            entry.reloading(false);
            entry.lastUpdatedAt(System.currentTimeMillis());
            entry.version().incrementAndGet();
        }
    }

    private void failReload(CacheEntry entry, Exception e) {
        synchronized (entry.monitor()) {
            entry.reloading(false);
            entry.lastUpdatedAt(System.currentTimeMillis());
            entry.version().incrementAndGet();
        }
    }

    private DocumentTreeNode snapshot(CacheEntry entry) {
        synchronized (entry.monitor()) {
            return toImmutable(entry.rootNode());
        }
    }

    private void buildChildrenLive(Path directory, Path root, MutableTreeNode parent, CacheEntry entry) {
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> paths = stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase())).toList();
            List<MutableTreeNode> builtChildren = new ArrayList<>();
            for (Path path : paths) {
                MutableTreeNode child = toFullNodeLive(path, root, entry);
                if (child != null) {
                    builtChildren.add(child);
                    synchronized (entry.monitor()) {
                        parent.children(new ArrayList<>(builtChildren));
                        entry.lastUpdatedAt(System.currentTimeMillis());
                        entry.version().incrementAndGet();
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build full tree for " + directory, e);
        }
    }

    private List<DocumentTreeNode> loadChildrenInternal(Path directory, String requestedRoot) {
        try (Stream<Path> stream = Files.list(directory)) {
            Path root = ensureRoot(requestedRoot);
            return stream.sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase())).map(path -> toNode(root, path)).filter(Objects::nonNull).toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load children for " + directory, e);
        }
    }

    private DocumentTreeNode toNode(Path root, Path path) {
        try {
            Path relative = root.relativize(path);
            String id = encode(relative);
            if (Files.isDirectory(path)) {
                boolean hasVisibleChildren = hasVisibleChildren(path);
                if (!hasVisibleChildren) return null;
                return new DocumentTreeNode(path.getFileName().toString(), relative.toString(), false, id, "directory", true, false, 0, List.of(), false);
            }
            if (!isSupportedDocument(path)) return null;
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new DocumentTreeNode(path.getFileName().toString(), relative.toString(), true, id, detectFileType(path), false, size == 0, size, List.of(), true);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private MutableTreeNode toFullNodeLive(Path path, Path root, CacheEntry entry) {
        try {
            Path relative = root.relativize(path);
            String id = encode(relative);
            if (Files.isDirectory(path)) {
                MutableTreeNode folder = new MutableTreeNode(path.getFileName().toString(), relative.toString(), false, id, "directory", true, false, 0);
                buildChildrenLive(path, root, folder, entry);
                if (folder.children().isEmpty()) return null;
                return folder;
            }
            if (!isSupportedDocument(path)) return null;
            long size = Files.exists(path) ? Files.size(path) : 0;
            return new MutableTreeNode(path.getFileName().toString(), relative.toString(), true, id, detectFileType(path), false, size == 0, size);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private DocumentTreeNode toImmutable(MutableTreeNode node) {
        return new DocumentTreeNode(node.name(), node.relativePath(), node.document(), node.id(), node.fileType(), node.expandable(), node.empty(), node.sizeInBytes(), node.children().stream().map(this::toImmutable).toList(), true);
    }

    private boolean hasVisibleChildren(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.anyMatch(path -> {
                try {
                    if (Files.isDirectory(path)) return hasVisibleChildren(path);
                    return isSupportedDocument(path);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (IllegalStateException e) {
            if (e.getCause() instanceof IOException ioException) throw ioException;
            throw e;
        }
    }

    private boolean isSupportedDocument(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return fileName.endsWith(".docx") || fileName.endsWith(".xlsx") || fileName.endsWith(".pptx") || fileName.endsWith(".pdf") || fileName.endsWith(".txt") || fileName.endsWith(".html") || fileName.endsWith(".htm") || fileName.endsWith(".md");
    }

    private String detectFileType(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".docx")) return "docx";
        if (fileName.endsWith(".xlsx")) return "xlsx";
        if (fileName.endsWith(".pptx")) return "pptx";
        if (fileName.endsWith(".pdf")) return "pdf";
        if (fileName.endsWith(".txt")) return "txt";
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) return "html";
        if (fileName.endsWith(".md")) return "md";
        return "unknown";
    }

    private Path ensureRoot(String requestedRoot) {
        try {
            Path configuredRoot = requestedRoot == null || requestedRoot.isBlank() ? Path.of(libraryRoot) : Path.of(requestedRoot);
            Path root = configuredRoot.toAbsolutePath().normalize();
            if (!Files.exists(root)) Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize library root", e);
        }
    }

    private String displayName(Path root) {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    private Path resolveFromId(String id, boolean expectDirectory, String requestedRoot) {
        if (id == null || id.isBlank()) return ensureRoot(requestedRoot);
        Path root = ensureRoot(requestedRoot);
        Path relative = decode(id);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IllegalArgumentException("Path escapes library root");
        if (!Files.exists(resolved)) throw new IllegalArgumentException("Document not found");
        if (expectDirectory && !Files.isDirectory(resolved)) throw new IllegalArgumentException("Folder not found");
        if (!expectDirectory && !Files.isRegularFile(resolved)) throw new IllegalArgumentException("Document not found");
        return resolved;
    }

    private String encode(Path relative) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(relative.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Path decode(String id) {
        if (id == null || id.isBlank()) return Path.of("");
        return Path.of(new String(Base64.getUrlDecoder().decode(id), StandardCharsets.UTF_8));
    }

    public record DocumentTreeNode(String name, String relativePath, boolean document, String id, String fileType, boolean expandable, boolean empty, long sizeInBytes, List<DocumentTreeNode> children, boolean loaded) {
        public DocumentTreeNode {
            children = List.copyOf(Objects.requireNonNullElse(children, List.of()));
        }
    }

    public record StoredDocument(Path path, String name, String fileType, boolean empty, long sizeInBytes, byte[] content) {}

    public record TreeReloadStatus(String root, boolean reloading, long reloadStartedAt, long lastUpdatedAt, long version, long elapsedMs) {}

    private static final class MutableTreeNode {
        private final String name;
        private final String relativePath;
        private final boolean document;
        private final String id;
        private final String fileType;
        private final boolean expandable;
        private final boolean empty;
        private final long sizeInBytes;
        private List<MutableTreeNode> children = new ArrayList<>();

        private MutableTreeNode(String name, String relativePath, boolean document, String id, String fileType, boolean expandable, boolean empty, long sizeInBytes) {
            this.name = name;
            this.relativePath = relativePath;
            this.document = document;
            this.id = id;
            this.fileType = fileType;
            this.expandable = expandable;
            this.empty = empty;
            this.sizeInBytes = sizeInBytes;
        }

        String name() { return name; }
        String relativePath() { return relativePath; }
        boolean document() { return document; }
        String id() { return id; }
        String fileType() { return fileType; }
        boolean expandable() { return expandable; }
        boolean empty() { return empty; }
        long sizeInBytes() { return sizeInBytes; }
        List<MutableTreeNode> children() { return children; }
        void children(List<MutableTreeNode> children) { this.children = children; }
    }

    private static final class CacheEntry {
        private final Path root;
        private final AtomicLong version;
        private final Object monitor = new Object();
        private MutableTreeNode rootNode;
        private boolean reloading;
        private long reloadStartedAt;
        private long lastUpdatedAt;

        private CacheEntry(Path root, MutableTreeNode rootNode, AtomicLong version, boolean reloading, long reloadStartedAt, long lastUpdatedAt) {
            this.root = root;
            this.rootNode = rootNode;
            this.version = version;
            this.reloading = reloading;
            this.reloadStartedAt = reloadStartedAt;
            this.lastUpdatedAt = lastUpdatedAt;
        }

        Path root() { return root; }
        AtomicLong version() { return version; }
        Object monitor() { return monitor; }
        MutableTreeNode rootNode() { return rootNode; }
        void rootNode(MutableTreeNode rootNode) { this.rootNode = rootNode; }
        boolean reloading() { return reloading; }
        void reloading(boolean reloading) { this.reloading = reloading; }
        long reloadStartedAt() { return reloadStartedAt; }
        void reloadStartedAt(long reloadStartedAt) { this.reloadStartedAt = reloadStartedAt; }
        long lastUpdatedAt() { return lastUpdatedAt; }
        void lastUpdatedAt(long lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }
    }
}
