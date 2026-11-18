package io.commenthub.comment.service;

import io.commenthub.comment.config.Toggles;
import io.commenthub.comment.mapper.CommentLikeMapper;
import io.commenthub.comment.mapper.CommentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LikeServiceTest {
    Toggles toggles = new Toggles();
    CommentLikeMapper likes = mock(CommentLikeMapper.class);
    CommentMapper comments = mock(CommentMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
    @SuppressWarnings("unchecked") SetOperations<String, String> sets = mock(SetOperations.class);
    EventPublisher publisher = mock(EventPublisher.class);
    TransactionTemplate tx = mock(TransactionTemplate.class);
    LikeService svc;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(tx.execute(any())).thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        svc = new LikeService(toggles, likes, comments, redis, publisher, tx);
    }

    @Test
    void dbModeDuplicateLikeDoesNotTouchCounter() {
        toggles.likeMode = Toggles.LikeMode.DB;
        when(likes.insertIgnore(1, 9)).thenReturn(0);
        when(comments.likeCount(9)).thenReturn(5);
        LikeService.LikeResult r = svc.like(1, 9);
        assertThat(r).isEqualTo(new LikeService.LikeResult(false, 5));
        verify(comments, never()).addLike(anyLong(), anyInt());
    }

    @Test
    void dbModeMissingCommentThrows() {
        toggles.likeMode = Toggles.LikeMode.DB;
        when(likes.insertIgnore(1, 9)).thenReturn(1);
        when(comments.addLike(9, 1)).thenReturn(0);
        assertThatThrownBy(() -> svc.like(1, 9)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void redisModeInitialisesCounterFromDbWhenKeyMissing() {
        toggles.likeMode = Toggles.LikeMode.REDIS;
        when(likes.insertIgnore(1, 9)).thenReturn(1);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);
        when(comments.likeCount(9)).thenReturn(100);
        when(values.increment("like:cnt:9", 1)).thenReturn(101L);
        LikeService.LikeResult r = svc.like(1, 9);
        assertThat(r).isEqualTo(new LikeService.LikeResult(true, 101));
        verify(values).setIfAbsent("like:cnt:9", "100");
        verify(publisher).publish(eq("like-changed"), eq("9"), any());
    }

    @Test
    void redisModeDuplicateLikeIsIdempotentAndPublishesNothing() {
        toggles.likeMode = Toggles.LikeMode.REDIS;
        when(likes.insertIgnore(1, 9)).thenReturn(0);
        when(values.get("like:cnt:9")).thenReturn("7");
        assertThat(svc.like(1, 9)).isEqualTo(new LikeService.LikeResult(false, 7));
        verifyNoInteractions(publisher);
    }

    @Test
    void redisModeLikeOnMissingCommentRollsBackLikeRow() {
        toggles.likeMode = Toggles.LikeMode.REDIS;
        when(likes.insertIgnore(1, 9)).thenReturn(1);
        when(redis.execute(any(RedisScript.class), anyList(), anyString())).thenReturn(null);
        when(comments.likeCount(9)).thenReturn(null);
        assertThatThrownBy(() -> svc.like(1, 9)).isInstanceOf(NotFoundException.class);
        verify(likes).delete(1, 9);
    }
}
