package io.commenthub.comment.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

public interface OutboxMapper {
    @Insert("INSERT INTO event_outbox (topic, msg_key, payload, trace_id) VALUES (#{topic}, #{key}, #{payload}, #{traceId})")
    int insert(@Param("topic") String topic, @Param("key") String key, @Param("payload") String payload, @Param("traceId") String traceId);

    @Select("SELECT id, topic, msg_key AS msgKey, payload, trace_id AS traceId FROM event_outbox ORDER BY id LIMIT #{n}")
    List<Map<String, Object>> oldest(@Param("n") int n);

    @Delete("DELETE FROM event_outbox WHERE id = #{id}")
    int delete(@Param("id") long id);
}
