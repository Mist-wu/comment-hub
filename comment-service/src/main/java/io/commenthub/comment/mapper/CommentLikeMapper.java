package io.commenthub.comment.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface CommentLikeMapper {
    /** 唯一键 (user_id, comment_id) 挡住重复点赞：返回 1 表示新点赞，0 表示已点过。 */
    @Insert("INSERT IGNORE INTO comment_like (user_id, comment_id) VALUES (#{userId}, #{commentId})")
    int insertIgnore(@Param("userId") long userId, @Param("commentId") long commentId);

    @Delete("DELETE FROM comment_like WHERE user_id = #{userId} AND comment_id = #{commentId}")
    int delete(@Param("userId") long userId, @Param("commentId") long commentId);

    @Select("SELECT COUNT(*) FROM comment_like WHERE comment_id = #{commentId}")
    long countByComment(@Param("commentId") long commentId);
}
