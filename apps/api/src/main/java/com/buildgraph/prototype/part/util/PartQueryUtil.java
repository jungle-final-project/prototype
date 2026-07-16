package com.buildgraph.prototype.part.util;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import java.util.Map;

public final class PartQueryUtil {

    private PartQueryUtil() {
    }

    /* parts 조회 결과를 Tool에서 공통으로 사용하는 객체로 변환한다. */
    @SuppressWarnings("unchecked")
    public static ToolBuildPart toolPart(Map<String, Object> row) {
        return new ToolBuildPart(
                numberLong(row.get("internal_id")),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                (Map<String, Object>) DbValueMapper.json(row, "attributes", Map.of()),
                DbValueMapper.integer(row, "quantity")
        );
    }

    public static Long numberLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }
}
