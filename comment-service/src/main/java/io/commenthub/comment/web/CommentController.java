package io.commenthub.comment.web;

import io.commenthub.comment.domain.CommentView;
import io.commenthub.comment.domain.Page;
import io.commenthub.comment.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class CommentController {
    private final CommentWriteService writer;
    private final CommentQueryService reader;
    private final LikeService likes;
    private final CounterService counter;

    public CommentController(CommentWriteService writer, CommentQueryService reader, LikeService likes, CounterService counter) {
        this.writer = writer;
        this.reader = reader;
        this.likes = likes;
        this.counter = counter;
    }

    public record PostReq(@NotNull Long userId, @NotBlank @Size(max = 500) String content) {}

    public record LikeReq(@NotNull Long userId) {}

    @PostMapping("/videos/{videoId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public CommentView post(@PathVariable long videoId, @Valid @RequestBody PostReq req) {
        return writer.post(videoId, req.userId(), req.content());
    }

    @GetMapping("/videos/{videoId}/comments")
    public Page list(@PathVariable long videoId,
                     @RequestParam(defaultValue = "latest") String sort,
                     @RequestParam(required = false) String cursor,
                     @RequestParam(defaultValue = "20") int size) {
        int s = Math.min(Math.max(size, 1), 50);
        return "hot".equals(sort) ? reader.hot(videoId, cursor, s) : reader.latest(videoId, cursor, s);
    }

    /** 朴素版分页，只用于和游标分页对比。 */
    @GetMapping("/videos/{videoId}/comments/by-page")
    public Page byPage(@PathVariable long videoId, @RequestParam(defaultValue = "1") int page,
                       @RequestParam(defaultValue = "20") int size) {
        return reader.latestByOffset(videoId, page, Math.min(Math.max(size, 1), 50));
    }

    @GetMapping("/videos/{videoId}/comment-count")
    public Map<String, Object> count(@PathVariable long videoId) {
        return Map.of("videoId", videoId, "count", counter.commentCount(videoId));
    }

    @PostMapping("/comments/{commentId}/like")
    public LikeService.LikeResult like(@PathVariable long commentId, @Valid @RequestBody LikeReq req) {
        return likes.like(req.userId(), commentId);
    }

    @DeleteMapping("/comments/{commentId}/like")
    public LikeService.LikeResult unlike(@PathVariable long commentId, @RequestParam long userId) {
        return likes.unlike(userId, commentId);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
