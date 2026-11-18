package io.commenthub.comment.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface VideoMapper {
    @Select("SELECT comment_count FROM video WHERE id = #{id}")
    Long commentCount(@Param("id") long id);

    @Select("SELECT id FROM video")
    List<Long> allIds();

    @Select("SELECT id FROM video ORDER BY comment_count DESC LIMIT #{n}")
    List<Long> topByComments(@Param("n") int n);
}
