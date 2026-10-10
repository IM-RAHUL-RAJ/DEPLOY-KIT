package com.trading.tradeapi.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

@Mapper
public interface UserMapper {

    /** user_id, uuid, status, and user_type ("admin" or "client", derived from roles). */
    Map<String, Object> findUserByUuid(@Param("uuid") String uuid);
}
