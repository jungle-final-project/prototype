package com.buildgraph.prototype.part;

import java.util.Map;

public record ToolBuildPart(
        Long internalId,
        String publicId,
        String category,
        String name,
        String manufacturer,
        Integer price,
        Map<String, Object> attributes,
        Integer quantity
) {
    // 대부분의 호출처는 수량 개념이 없다(후보 평가 = 단품 1개 가정). 견적 드래프트처럼
    // 수량이 실재하는 경로만 8-인자 생성자를 쓴다.
    public ToolBuildPart(
            Long internalId,
            String publicId,
            String category,
            String name,
            String manufacturer,
            Integer price,
            Map<String, Object> attributes
    ) {
        this(internalId, publicId, category, name, manufacturer, price, attributes, 1);
    }

    public int effectiveQuantity() {
        return quantity == null || quantity < 1 ? 1 : quantity;
    }
}
