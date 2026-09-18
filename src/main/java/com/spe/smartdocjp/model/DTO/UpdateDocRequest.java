package com.spe.smartdocjp.model.DTO;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "文档更新请求参数")
public class UpdateDocRequest {

    @Schema(description = "文档标题", example = "我的新文档标题")
    private String title;

}
