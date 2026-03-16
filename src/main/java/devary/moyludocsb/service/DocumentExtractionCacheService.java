package devary.moyludocsb.service;

import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;

@Service
public class DocumentExtractionCacheService {

    @Value("${moyludoc.cache.extraction.ttl:PT2H}")
    Duration ttl;

    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public Object getOrCompute(DocumentLibraryService.StoredDocument stored, Supplier<Object> extractor) {
        long now = System.currentTimeMillis();
        FileFingerprint fingerprint = fingerprintOf(stored);
        String key = stored.path().toAbsolutePath().normalize().toString();

        CacheEntry cached = cache.get(key);
        if (cached != null && !cached.isExpired(now) && cached.fingerprint().equals(fingerprint)) {
            return cached.value();
        }

        Object value = extractor.get();
        cache.put(key, new CacheEntry(fingerprint, value, now + ttl.toMillis()));
        return value;
    }

    public void invalidate(Path path) {
        if (path == null) {
            return;
        }
        cache.remove(path.toAbsolutePath().normalize().toString());
    }

    private FileFingerprint fingerprintOf(DocumentLibraryService.StoredDocument stored) {
        Path path = stored.path().toAbsolutePath().normalize();
        try {
            long lastModified = Files.getLastModifiedTime(path).toMillis();
            return new FileFingerprint(path.toString(), stored.sizeInBytes(), lastModified);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to fingerprint document for cache", e);
        }
    }

    private record CacheEntry(FileFingerprint fingerprint, Object value, long expiresAtMillis) {
        private boolean isExpired(long now) {
            return now >= expiresAtMillis;
        }
    }

    private record FileFingerprint(String path, long sizeInBytes, long lastModifiedMillis) {
        private FileFingerprint {
            Objects.requireNonNull(path, "path");
        }
    }
}
