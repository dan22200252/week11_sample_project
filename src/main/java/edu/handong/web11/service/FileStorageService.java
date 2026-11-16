package edu.handong.web11.service;

import edu.handong.web11.domain.Attachment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class FileStorageService {
    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "gif", "pdf", "txt");
    private final Path uploadDir;

    public FileStorageService(@Value("${app.upload-dir}") String path) throws IOException {
        uploadDir = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(uploadDir);
    }

    public Attachment store(MultipartFile file) {
        //업로드 파일을 검증합니다.
        if (file.isEmpty()) throw new IllegalArgumentException("빈 파일은 업로드할 수 없습니다.");

        String original = file.getOriginalFilename();
        // 원본 파일명이 비어 있거나 너무 길면 거부합니다. 경로에 쓰이는 문자가 들어간 이름도 거부합니다.
        if (original == null || original.isBlank() || original.contains("/") || original.contains("\\") || original.contains(".."))
            throw new IllegalArgumentException("잘못된 파일 이름입니다.");
        if (original.length() > 240) throw new IllegalArgumentException("파일 이름이 너무 깁니다.");
        int dot = original.lastIndexOf('.');
        String ext = dot < 0 ? "" : original.substring(dot + 1).toLowerCase(Locale.ROOT);
        // 파일명 끝에서 뽑은 확장자를 허용 목록과 비교합니다. 프로젝트에서 허용한 형식만 받도록 합니다.
        if (!ALLOWED.contains(ext)) throw new IllegalArgumentException("허용 형식: jpg, jpeg, png, gif, pdf, txt");
        // 	업로드 파일 한 개가 10MB를 넘으면 저장 전에 거부합니다.
        if (file.getSize() > 10L * 1024 * 1024) throw new IllegalArgumentException("파일은 10MB 이하만 가능합니다.");

        // UUID 기반 저장 파일명을 생성합니다.
        String stored = UUID.randomUUID() + "." + ext;
        // 저장 경로를 만들고 정리합니다.
        Path target = uploadDir.resolve(stored).normalize();
        // 업로드 폴더 밖으로 벗어나는지 확인합니다.
        if (!target.startsWith(uploadDir)) throw new IllegalArgumentException("잘못된 파일 경로입니다.");
        try (InputStream input = file.getInputStream()) {
            // 실제 파일을 저장합니다.
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            // Teaching sample: MIME type is inferred by extension, not trusted from request headers.
            String contentType = switch (ext) {
                case "jpg", "jpeg" -> "image/jpeg";
                case "png" -> "image/png";
                case "gif" -> "image/gif";
                case "pdf" -> "application/pdf";
                default -> "text/plain";
            };

            // DB 저장에 필요한 메타데이터를 반환합니다.
            return new Attachment(original, stored, contentType, file.getSize());
        }
        // 저장 중 입출력 오류가 발생하면 남은 파일을 삭제 시도하고 HTTP 500 오류로 처리합니다.
        catch (IOException e) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 저장 실패", e);
        }
    }

    public Resource load(Attachment attachment) {
        Path target = uploadDir.resolve(attachment.getStoredFilename()).normalize();
        if (!target.startsWith(uploadDir) || !Files.isRegularFile(target))
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "파일이 없습니다.");
        return new PathResource(target);
    }

    public void deleteIfExists(Attachment attachment) {
        try {
            Files.deleteIfExists(uploadDir.resolve(attachment.getStoredFilename()).normalize());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "파일 삭제 실패", e);
        }
    }
}
