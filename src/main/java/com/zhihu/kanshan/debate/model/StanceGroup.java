package com.zhihu.kanshan.debate.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class StanceGroup {
    private String id;           // e.g. "pro", "con", "neutral", "insider"
    private String label;        // e.g. "支持转行派"
    private String description;  // one-line stance summary
    private String emoji;        // visual marker for UI
    private List<Answer> answers;
    private List<String> keyArguments; // top 3 arguments for this stance
    private String topAuthorName;
    private String topAuthorUrl;
}
