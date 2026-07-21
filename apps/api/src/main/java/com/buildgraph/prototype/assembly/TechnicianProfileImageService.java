package com.buildgraph.prototype.assembly;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TechnicianProfileImageService {
    private static final long MAX_FILE_SIZE = 1024L * 1024L;
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final Path storageRoot;

    public TechnicianProfileImageService(
            @Value("${buildgraph.technician-profile-images.storage-path:data/technician-profile-images}") String storagePath
    ) {
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize();
    }

    public Map<String, Object> upload(MultipartFile file) {
        ValidatedImage image = validate(file);
        String fileName = UUID.randomUUID() + image.extension();
        Path target = storageRoot.resolve(fileName).normalize();
        if (!target.startsWith(storageRoot)) {
            throw fileValidationError("INVALID_FILE_NAME", "Invalid profile image file name.");
        }

        try {
            Files.createDirectories(storageRoot);
            Files.write(target, image.bytes());
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_STORAGE_ERROR", "Could not store profile image.");
        }

        return MockData.map(
                "profileImageUrl", "/api/technician-profile-images/" + fileName,
                "fileName", fileName,
                "fileSize", image.bytes().length,
                "contentType", image.contentType()
        );
    }

    public ResponseEntity<Resource> image(String fileName) {
        String safeFileName = storedFileName(fileName);
        Path path = storageRoot.resolve(safeFileName).normalize();
        if (!path.startsWith(storageRoot) || !Files.isRegularFile(path)) {
            throw notFound();
        }

        try {
            Resource resource = new UrlResource(path.toUri());
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentTypeForFileName(safeFileName)))
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic())
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + safeFileName + "\"")
                    .contentLength(Files.size(path))
                    .body(resource);
        } catch (MalformedURLException exception) {
            throw notFound();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_READ_ERROR", "Could not read profile image.");
        }
    }

    private static ValidatedImage validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw fileValidationError("MISSING_FILE", "A profile image file is required.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw fileValidationError("FILE_SIZE_EXCEEDED", "Profile images must be 1MiB or smaller.");
        }

        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw fileValidationError("INVALID_MIME", "Only JPG, PNG, and WebP images are allowed.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw fileValidationError("READ_FAILED", "Could not read profile image.");
        }
        if (!matchesMagicBytes(contentType, bytes)) {
            throw fileValidationError("INVALID_IMAGE", "Profile image content does not match its MIME type.");
        }

        return new ValidatedImage(contentType, extensionForContentType(contentType), bytes);
    }

    private static boolean matchesMagicBytes(String contentType, byte[] bytes) {
        return switch (contentType) {
            case "image/jpeg" -> bytes.length >= 3
                    && (bytes[0] & 0xff) == 0xff
                    && (bytes[1] & 0xff) == 0xd8
                    && (bytes[2] & 0xff) == 0xff;
            case "image/png" -> bytes.length >= 8
                    && (bytes[0] & 0xff) == 0x89
                    && bytes[1] == 0x50
                    && bytes[2] == 0x4e
                    && bytes[3] == 0x47
                    && bytes[4] == 0x0d
                    && bytes[5] == 0x0a
                    && bytes[6] == 0x1a
                    && bytes[7] == 0x0a;
            case "image/webp" -> bytes.length >= 12
                    && bytes[0] == 0x52
                    && bytes[1] == 0x49
                    && bytes[2] == 0x46
                    && bytes[3] == 0x46
                    && bytes[8] == 0x57
                    && bytes[9] == 0x45
                    && bytes[10] == 0x42
                    && bytes[11] == 0x50;
            default -> false;
        };
    }

    private static String extensionForContentType(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> throw fileValidationError("INVALID_MIME", "Only JPG, PNG, and WebP images are allowed.");
        };
    }

    private static String storedFileName(String fileName) {
        String text = fileName == null ? "" : fileName.trim();
        if (!text.matches("[0-9a-fA-F-]{36}\\.(jpg|png|webp)")) {
            throw notFound();
        }
        return text;
    }

    private static String contentTypeForFileName(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        throw notFound();
    }

    private static ApiException fileValidationError(String reason, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        return new ApiException(HttpStatus.BAD_REQUEST, "FILE_VALIDATION_ERROR", message, details);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Profile image not found.");
    }

    private record ValidatedImage(String contentType, String extension, byte[] bytes) {
    }
}
