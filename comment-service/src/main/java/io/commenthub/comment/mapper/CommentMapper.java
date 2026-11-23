package io.commenthub.comment.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import io.commenthub.comment.domain.Comment;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface CommentMapper extends BaseMapper<Comment> {

    String COLS = "id, video_id, user_id, content, like_count, status, created_at";

    /** 游标分页（最新）：只走 idx_video_created 的一段范围，与页码无关。 */
    @Select("SELECT " + COLS + " FROM comment WHERE video_id = #{videoId} AND status <> 3 "
            + "AND (created_at < #{ts} OR (created_at = #{ts} AND id < #{id})) "
            + "ORDER BY created_at DESC, id DESC LIMIT #{size}")
    List<Comment> latestAfter(@Param("videoId") long videoId, @Param("ts") LocalDateTime ts,
                              @Param("id") long id, @Param("size") int size);

    /** 朴素版：LIMIT offset。offset 越大，要扫描并丢弃的行越多。 */
    @Select("SELECT " + COLS + " FROM comment WHERE video_id = #{videoId} AND status <> 3 "
            + "ORDER BY created_at DESC, id DESC LIMIT #{offset}, #{size}")
    List<Comment> latestOffset(@Param("videoId") long videoId, @Param("offset") long offset, @Param("size") int size);

    /** 朴素版最热：实时 ORDER BY like_count，大视频上是全分区 filesort。 */
    @Select("SELECT " + COLS + " FROM comment WHERE video_id = #{videoId} AND status <> 3 "
            + "ORDER BY like_count DESC, id DESC LIMIT #{offset}, #{size}")
    List<Comment> hotOffset(@Param("videoId") long videoId, @Param("offset") long offset, @Param("size") int size);

    /** 热榜翻出 ZSet 之后按点赞数和 id 续页，不靠 offset 对齐两套顺序。 */
    @Select("SELECT " + COLS + " FROM comment WHERE video_id = #{videoId} AND status <> 3 "
            + "AND (like_count < #{like} OR (like_count = #{like} AND id < #{id})) "
            + "ORDER BY like_count DESC, id DESC LIMIT #{size}")
    List<Comment> hotAfter(@Param("videoId") long videoId, @Param("like") long like,
                           @Param("id") long id, @Param("size") int size);

    /** 构建最热 ZSet 用：只取 id 和 like_count，减少回表数据量。 */
    @Select("SELECT id, like_count FROM comment WHERE video_id = #{videoId} AND status <> 3 "
            + "ORDER BY like_count DESC, id DESC LIMIT #{size}")
    List<Comment> topByLikes(@Param("videoId") long videoId, @Param("size") int size);

    @Select("<script>SELECT " + COLS + " FROM comment WHERE id IN "
            + "<foreach collection='ids' item='i' open='(' separator=',' close=')'>#{i}</foreach></script>")
    List<Comment> byIds(@Param("ids") Collection<Long> ids);

    /** 朴素点赞：在事务里对同一行 +1，高并发下所有事务排队等这一行的 X 锁。 */
    @Update("UPDATE comment SET like_count = like_count + #{delta} WHERE id = #{id}")
    int addLike(@Param("id") long id, @Param("delta") int delta);

    @Select("SELECT like_count FROM comment WHERE id = #{id}")
    Integer likeCount(@Param("id") long id);

    /** 朴素评论数：大视频上要扫描上百万条索引项。 */
    @Select("SELECT COUNT(*) FROM comment WHERE video_id = #{videoId}")
    long countByVideo(@Param("videoId") long videoId);

    @Update("UPDATE comment SET like_count = #{n} WHERE id = #{id}")
    int setLikeCount(@Param("id") long id, @Param("n") long n);
}
